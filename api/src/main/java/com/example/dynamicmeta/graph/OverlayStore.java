package com.example.dynamicmeta.graph;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Keeps the {@link Overlay} — the edits a person has made over a discovered
 * {@link SchemaGraph} — in one JSON file. The file is the only thing this
 * store touches: nothing here ever writes to a source database.
 * <p>
 * Loading an overlay whose file does not exist yet returns an empty overlay
 * and creates nothing; a file that is not a readable overlay fails with an
 * {@link IllegalStateException} naming the file. Unknown JSON properties are
 * ignored, so an overlay written by a newer version still loads.
 * <p>
 * Every mutator validates the change, updates the overlay and saves it
 * immediately, so a new store instance — or a restart — sees the edit. A
 * save writes a temporary file in the same folder as the overlay file and
 * moves it over the original, so an interrupted write can never corrupt the
 * previous overlay. The JSON is pretty printed, so the file stays readable
 * and diffable. All methods are synchronized: one store is safe to share.
 * <p>
 * Security settings such as hidden columns stay in configuration; they are
 * never stored in the overlay.
 */
public final class OverlayStore {

    private final Path file;
    private final ObjectMapper mapper;
    private Overlay overlay;

    /** Reads the overlay file at the given path, or starts empty. */
    public OverlayStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.overlay = load();
    }

    /** Reads the overlay file at the given path, or starts empty. */
    public OverlayStore(String file) {
        this(Path.of(file));
    }

    /**
     * Reads the overlay file again and makes it the store's state, replacing
     * whatever was loaded or saved before. Returns an empty overlay when the
     * file does not exist.
     *
     * @throws IllegalStateException when the file exists but is not a
     *         readable overlay; the message names the file
     */
    public synchronized Overlay load() {
        if (!Files.exists(file)) {
            overlay = Overlay.empty();
            return overlay;
        }
        try {
            overlay = mapper.readValue(file.toFile(), Overlay.class);
        } catch (FileNotFoundException | NoSuchFileException missing) {
            // The file vanished between the existence check and this read;
            // that is the missing-file case, not a broken overlay.
            overlay = Overlay.empty();
        } catch (IOException e) {
            throw new IllegalStateException("overlay file " + file
                    + " is not a readable overlay: " + e.getMessage(), e);
        }
        return overlay;
    }

    /**
     * The overlay as last loaded or saved; immutable, so it may be held and
     * read without synchronizing on this store.
     */
    public synchronized Overlay overlay() {
        return overlay;
    }

    /**
     * Labels the entity or column id, replacing any previous label for it,
     * and saves immediately.
     *
     * @throws IllegalArgumentException when the id or label is blank, the
     *         label is longer than {@link Overlay#MAX_LABEL_LENGTH} or the
     *         description longer than {@link Overlay#MAX_DESCRIPTION_LENGTH}
     */
    public synchronized void setLabel(String id, String label, String description) {
        apply(overlay.withLabel(id, label, description));
    }

    /** Removes the label of the id, if any, and saves immediately. */
    public synchronized void removeLabel(String id) {
        apply(overlay.withoutLabel(id));
    }

    /**
     * Confirms or rejects the relationship and saves immediately.
     *
     * @throws IllegalArgumentException when the status is anything but
     *         {@code confirmed} or {@code rejected}, ignoring case
     */
    public synchronized void decide(String relationshipId, String status) {
        apply(overlay.withDecision(relationshipId, status));
    }

    /** Removes the decision for the relationship, if any, and saves immediately. */
    public synchronized void clearDecision(String relationshipId) {
        apply(overlay.withoutDecision(relationshipId));
    }

    /**
     * Asserts a manual relationship between the two column ids and saves
     * immediately; asserting the same pair again changes nothing.
     *
     * @throws IllegalArgumentException when either end is not a column id
     */
    public synchronized void addManual(String from, String to) {
        apply(overlay.withManualRelationship(from, to));
    }

    /**
     * Removes the manual relationship named by the given
     * {@link GraphIds#relationshipId}, if any, and saves immediately.
     */
    public synchronized void removeManual(String relationshipId) {
        apply(overlay.withoutManualRelationship(relationshipId));
    }

    /**
     * Saves only when the change actually altered the overlay. The file is
     * written first and the in-memory overlay replaced only after the write
     * succeeds, so a failed save never leaves the API serving an edit that the
     * file (and so the next restart) does not have.
     */
    private void apply(Overlay next) {
        if (next == overlay) {
            return;
        }
        save(next);
        overlay = next;
    }

    /**
     * Writes the overlay to a temporary file in the overlay file's folder,
     * then moves it over the original.
     */
    private void save(Overlay value) {
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory,
                    file.getFileName().toString() + "-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), value);
                try {
                    Files.move(temporary, file,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "overlay file " + file + " could not be written", e);
        }
    }
}
