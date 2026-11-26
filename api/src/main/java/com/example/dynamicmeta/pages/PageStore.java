package com.example.dynamicmeta.pages;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
 * Stores visual UI page documents as pretty-printed JSON files. Writes are
 * atomic, superseded pages are retained in a short history, and no operation
 * reads from or writes to a source database.
 */
public final class PageStore {

    private static final Pattern PAGE_ID = Pattern.compile("[a-z0-9-]{1,64}");
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    private static final int HISTORY_KEEP = 5;
    private static final String JSON_SUFFIX = ".json";

    private final Path directory;
    private final ObjectMapper mapper;

    /** Creates a page store and its folder when it does not exist. */
    public PageStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "page directory " + this.directory + " could not be created", e);
        }
    }

    public PageStore(String directory) {
        this(Path.of(directory));
    }

    /** Saves a page and returns the timestamps actually persisted. */
    public synchronized Page save(Page page) {
        Objects.requireNonNull(page, "page");
        validate(page);
        Path file = pageFile(page.id());
        Optional<Page> previous = read(file);
        Instant now = Instant.now();
        Instant createdAt = previous.map(Page::createdAt).orElse(now);
        Page stored = new Page(page.id(), page.name(), page.description(),
                page.document(), createdAt, now);
        try {
            previous.ifPresent(existing -> archiveUnchecked(existing, file));
            writeAtomically(file, target -> mapper.writeValue(target.toFile(), stored));
            return stored;
        } catch (IOException e) {
            throw new UncheckedIOException("page file " + file + " could not be written", e);
        }
    }

    /** Returns a page by id, or empty when it has not been saved. */
    public synchronized Optional<Page> get(String id) {
        validateId(id);
        return read(pageFile(id));
    }

    /** Lists all saved pages in id order. */
    public synchronized List<Page> list() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JSON_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> read(path).orElseThrow())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "page directory " + directory + " could not be listed", e);
        }
    }

    /** Deletes the current page file, retaining any history. */
    public synchronized boolean delete(String id) {
        validateId(id);
        Path file = pageFile(id);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("page file " + file + " could not be deleted", e);
        }
    }

    private void validate(Page page) {
        validateId(page.id());
        if (page.name() != null && page.name().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "page name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!page.document().isArray()) {
            throw new IllegalArgumentException("page document must be a JSON array");
        }
        try {
            if (mapper.writeValueAsBytes(page.document()).length > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException(
                        "page document must be at most 512 KB");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("page document could not be serialized", e);
        }
    }

    private void archiveUnchecked(Page previous, Path file) {
        try {
            Path historyDirectory = directory.resolve("history").resolve(previous.id());
            Files.createDirectories(historyDirectory);
            Path archived = historyDirectory.resolve(
                    previous.updatedAt().toString() + JSON_SUFFIX);
            writeAtomically(archived, target -> Files.copy(file, target,
                    StandardCopyOption.REPLACE_EXISTING));
            pruneHistory(historyDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "page history for " + previous.id() + " could not be written", e);
        }
    }

    private void pruneHistory(Path historyDirectory) throws IOException {
        List<Path> stale;
        try (Stream<Path> files = Files.list(historyDirectory)) {
            stale = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JSON_SUFFIX))
                    .sorted(Comparator.comparing(PageStore::historyInstant).reversed())
                    .skip(HISTORY_KEEP)
                    .toList();
        }
        for (Path path : stale) {
            Files.delete(path);
        }
    }

    private Optional<Page> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), Page.class));
        } catch (FileNotFoundException | NoSuchFileException missing) {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            throw unreadable(file, missing);
        } catch (IOException | IllegalArgumentException e) {
            throw unreadable(file, e);
        }
    }

    private IllegalStateException unreadable(Path file, Exception cause) {
        return new IllegalStateException(
                "page file " + file + " is not a readable page: " + cause.getMessage(), cause);
    }

    private void writeAtomically(Path target, WriteOperation operation) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(),
                target.getFileName().toString() + "-", ".tmp");
        try {
            operation.write(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path pageFile(String id) {
        return directory.resolve(id + JSON_SUFFIX);
    }

    private static Instant historyInstant(Path path) {
        String name = path.getFileName().toString();
        return Instant.parse(name.substring(0, name.length() - JSON_SUFFIX.length()));
    }

    private static void validateId(String id) {
        if (id == null || !PAGE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "page id '" + id + "' must match [a-z0-9-]{1,64}");
        }
    }

    private interface WriteOperation {
        void write(Path temporary) throws IOException;
    }
}
