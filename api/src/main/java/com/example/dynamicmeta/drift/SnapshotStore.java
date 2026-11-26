package com.example.dynamicmeta.drift;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Keeps the accepted baseline {@link SchemaSnapshot} of each source on disk:
 * one pretty-printed JSON file {@code <dir>/<source>.json} per source, plus a
 * short history of superseded baselines under {@code <dir>/history/<source>/},
 * named after the moment each was taken. The snapshot files are the only
 * thing this store touches: nothing here reads from or writes to a source
 * database.
 * <p>
 * A baseline for a source that was never saved reads back as
 * {@link Optional#empty()} and creates no files. Saving replaces the baseline
 * atomically — a temporary file in the same folder is moved over the original,
 * exactly like {@link com.example.dynamicmeta.graph.OverlayStore} — so an
 * interrupted write can never corrupt the accepted baseline. Before the
 * replacement lands, the previous baseline is copied into the source's
 * history folder as {@code <takenAt>.json} (UTC, {@code yyyyMMdd'T'HHmmss'Z'})
 * and only the newest ten history files are kept.
 * <p>
 * Source names must match {@code [a-z0-9-]{1,40}}: anything else fails with
 * an {@link IllegalArgumentException} instead of becoming a file name, which
 * is what keeps a crafted name such as {@code ../x} from escaping the folder.
 * <p>
 * A baseline file that exists but is not a readable snapshot fails with an
 * {@link IllegalStateException} naming the file, and is never deleted or
 * overwritten automatically — fixing or removing it is a human decision.
 * Saving over a baseline is likewise refused while that file is unreadable.
 * <p>
 * All methods are synchronized: one store is safe to share.
 */
public final class SnapshotStore {

    /** Names usable as a file name, so they cannot escape the snapshot folder. */
    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9-]{1,40}");
    /** Number of superseded baselines kept per source. */
    private static final int HISTORY_KEEP = 10;
    /** History file names, sortable chronologically as strings. */
    private static final DateTimeFormatter HISTORY_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String JSON_SUFFIX = ".json";

    private final Path directory;
    private final ObjectMapper mapper;

    /** Reads and writes baselines in the given folder, created when missing. */
    public SnapshotStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Reads and writes baselines in the given folder, created when missing. */
    public SnapshotStore(String directory) {
        this(Path.of(directory));
    }

    /**
     * The accepted baseline for the source, or {@link Optional#empty()} when
     * no baseline has been saved for it yet. Reading never creates the folder.
     *
     * @throws IllegalArgumentException when the source name does not match
     *         {@code [a-z0-9-]{1,40}}
     * @throws IllegalStateException when the baseline file exists but is not a
     *         readable snapshot; the message names the file and the file is
     *         left in place
     */
    public synchronized Optional<SchemaSnapshot> baseline(String source) {
        validateSource(source);
        return read(baselineFile(source));
    }

    /**
     * Accepts the snapshot as the source's new baseline: the previous baseline
     * (if any) is copied into {@code <dir>/history/<source>/} under its
     * {@code takenAt}, keeping the newest {@value #HISTORY_KEEP} files, and
     * the snapshot is then written atomically to {@code <dir>/<source>.json}.
     * The folder and the history folder are created when missing.
     *
     * @throws IllegalArgumentException when the snapshot's source name does
     *         not match {@code [a-z0-9-]{1,40}}
     * @throws IllegalStateException when an existing baseline cannot be read,
     *         so nothing is known to archive; the unreadable file is left in
     *         place and no save happens
     * @throws UncheckedIOException when the files cannot be written
     */
    public synchronized void saveBaseline(SchemaSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateSource(snapshot.source());
        Path file = baselineFile(snapshot.source());
        try {
            Files.createDirectories(directory);
            archivePrevious(snapshot.source(), file);
            writeAtomically(file, target -> mapper.writeValue(target.toFile(), snapshot));
        } catch (IOException e) {
            throw new UncheckedIOException("snapshot file " + file + " could not be written", e);
        }
    }

    /**
     * Copies the current baseline into the source's history folder under the
     * moment it was taken. Does nothing when there is no baseline yet.
     */
    private void archivePrevious(String source, Path file) throws IOException {
        Optional<SchemaSnapshot> previous = read(file);
        if (previous.isEmpty()) {
            return;
        }
        Path historyDirectory = directory.resolve("history").resolve(source);
        Files.createDirectories(historyDirectory);
        String name = HISTORY_STAMP.format(previous.orElseThrow().takenAt()) + JSON_SUFFIX;
        Path archived = historyDirectory.resolve(name);
        writeAtomically(archived, target -> Files.copy(file, target,
                StandardCopyOption.REPLACE_EXISTING));
        pruneHistory(historyDirectory);
    }

    /** Deletes all but the newest {@value #HISTORY_KEEP} history files. */
    private void pruneHistory(Path historyDirectory) throws IOException {
        List<Path> stale;
        try (Stream<Path> files = Files.list(historyDirectory)) {
            stale = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(JSON_SUFFIX))
                    // fixed-width UTC stamps sort chronologically as strings
                    .sorted(Comparator.comparing(
                            (Path p) -> p.getFileName().toString()).reversed())
                    .skip(HISTORY_KEEP)
                    .toList();
        }
        for (Path surplus : stale) {
            Files.delete(surplus);
        }
    }

    private Optional<SchemaSnapshot> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), SchemaSnapshot.class));
        } catch (FileNotFoundException | NoSuchFileException missing) {
            // The file vanished between the existence check and this read;
            // that is the missing-baseline case, not a broken file.
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("snapshot file " + file
                    + " is not a readable baseline: " + e.getMessage(), e);
        }
    }

    /**
     * Writes through a temporary file in the target's folder, then moves it
     * over the target so a half-written file is never left in its place.
     */
    private void writeAtomically(Path target, WriteOperation operation) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(),
                target.getFileName().toString() + "-", ".tmp");
        try {
            operation.write(temporary);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Writes the payload of a save into the temporary file. */
    private interface WriteOperation {
        void write(Path temporary) throws IOException;
    }

    private Path baselineFile(String source) {
        return directory.resolve(source + JSON_SUFFIX);
    }

    private static void validateSource(String source) {
        if (source == null || !SOURCE_NAME.matcher(source).matches()) {
            throw new IllegalArgumentException(
                    "snapshot source name '" + source + "' must match [a-z0-9-]{1,40}");
        }
    }
}
