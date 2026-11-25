package com.example.dynamicmeta.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The edits a person has made over a discovered {@link SchemaGraph}: a label
 * and a description for entity and column ids, a confirmed or rejected
 * decision for every relationship that asked for review, and relationships
 * between columns that discovery could not know about.
 * <p>
 * The overlay is stored as JSON by {@link OverlayStore}, apart from both
 * configuration and discovery, so rediscovering the schema never wipes what
 * a person has decided. Security settings such as hidden columns are
 * configuration, not overlay: nothing of the kind is stored here, and
 * nothing in the overlay is ever written to a source database.
 * <p>
 * Every id is lower-cased like the {@link GraphIds} schemes, so an entry
 * survives a database that reports its names in a different case. The
 * record is immutable and validated as a whole — including when it is read
 * back from JSON — so an overlay in memory is always well-formed: labels are
 * at most {@link #MAX_LABEL_LENGTH} characters, descriptions at most
 * {@link #MAX_DESCRIPTION_LENGTH}, and every decision value is
 * {@code confirmed} or {@code rejected}. The {@code with...} and
 * {@code without...} methods return a new instance, or {@code this} when the
 * change would alter nothing.
 *
 * @param labels                label and description by entity or column id
 * @param relationshipDecisions {@code confirmed} or {@code rejected} by
 *                              relationship id
 * @param manualRelationships   relationships a person asserted between two
 *                              column ids
 */
public record Overlay(Map<String, Label> labels,
                      Map<String, String> relationshipDecisions,
                      List<ManualRelationship> manualRelationships) {

    /** Longest label accepted for an entity or a column. */
    public static final int MAX_LABEL_LENGTH = 200;

    /** Longest description accepted for an entity or a column. */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    public Overlay {
        labels = normalizeLabels(labels);
        relationshipDecisions = normalizeDecisions(relationshipDecisions);
        manualRelationships = normalizeManualRelationships(manualRelationships);
    }

    /** An overlay with nothing decided yet. */
    public static Overlay empty() {
        return new Overlay(Map.of(), Map.of(), List.of());
    }

    /**
     * Returns the overlay with a label and description for the given entity
     * or column id, lower-cased, replacing any previous entry for it.
     *
     * @throws IllegalArgumentException when the id is blank, the label is
     *         blank or longer than {@link #MAX_LABEL_LENGTH}, or the
     *         description is longer than {@link #MAX_DESCRIPTION_LENGTH}
     */
    public Overlay withLabel(String id, String label, String description) {
        Label entry = new Label(label, description);
        String key = idKey(id);
        if (entry.equals(labels.get(key))) {
            return this;
        }
        Map<String, Label> next = new LinkedHashMap<>(labels);
        next.put(key, entry);
        return new Overlay(next, relationshipDecisions, manualRelationships);
    }

    /**
     * Returns the overlay without the label of the given entity or column
     * id, or {@code this} when there is none.
     */
    public Overlay withoutLabel(String id) {
        String key = idKey(id);
        if (!labels.containsKey(key)) {
            return this;
        }
        Map<String, Label> next = new LinkedHashMap<>(labels);
        next.remove(key);
        return new Overlay(next, relationshipDecisions, manualRelationships);
    }

    /**
     * Returns the overlay with the relationship confirmed or rejected.
     *
     * @throws IllegalArgumentException when the status is anything but
     *         {@code confirmed} or {@code rejected}, ignoring case
     */
    public Overlay withDecision(String relationshipId, String status) {
        String key = idKey(relationshipId);
        String value = decisionValue(status);
        if (value.equals(relationshipDecisions.get(key))) {
            return this;
        }
        Map<String, String> next = new LinkedHashMap<>(relationshipDecisions);
        next.put(key, value);
        return new Overlay(labels, next, manualRelationships);
    }

    /**
     * Returns the overlay without the decision for the given relationship
     * id, or {@code this} when there is none.
     */
    public Overlay withoutDecision(String relationshipId) {
        String key = idKey(relationshipId);
        if (!relationshipDecisions.containsKey(key)) {
            return this;
        }
        Map<String, String> next = new LinkedHashMap<>(relationshipDecisions);
        next.remove(key);
        return new Overlay(labels, next, manualRelationships);
    }

    /**
     * Returns the overlay with a manual relationship from one column to
     * another, or {@code this} when that pair is already asserted.
     *
     * @throws IllegalArgumentException when either end is not a
     *         {@link GraphIds#columnId} shape
     */
    public Overlay withManualRelationship(String from, String to) {
        ManualRelationship entry = new ManualRelationship(from, to);
        if (manualRelationships.contains(entry)) {
            return this;
        }
        List<ManualRelationship> next = new ArrayList<>(manualRelationships);
        next.add(entry);
        return new Overlay(labels, relationshipDecisions, next);
    }

    /**
     * Returns the overlay without the manual relationship named by the
     * given {@link GraphIds#relationshipId}, or {@code this} when there is
     * none.
     */
    public Overlay withoutManualRelationship(String relationshipId) {
        GraphIds.RelationshipId parts = GraphIds.parseRelationshipId(relationshipId);
        ManualRelationship entry = new ManualRelationship(parts.from(), parts.to());
        if (!manualRelationships.contains(entry)) {
            return this;
        }
        List<ManualRelationship> next = new ArrayList<>(manualRelationships);
        next.remove(entry);
        return new Overlay(labels, relationshipDecisions, next);
    }

    /**
     * A person's label and description for one entity or column id.
     *
     * @param label       short display name, at most {@link Overlay#MAX_LABEL_LENGTH}
     *                    characters
     * @param description longer explanation, at most
     *                    {@link Overlay#MAX_DESCRIPTION_LENGTH} characters,
     *                    null when there is none
     */
    public record Label(String label, String description) {

        public Label {
            label = required(label, "label", MAX_LABEL_LENGTH);
            description = optional(description, "description", MAX_DESCRIPTION_LENGTH);
        }
    }

    /**
     * One relationship a person asserted between two columns, named by
     * column ids so it can cross sources.
     *
     * @param from column id the relationship starts at
     * @param to   column id the relationship points at
     */
    public record ManualRelationship(String from, String to) {

        public ManualRelationship {
            from = columnId(from, "from");
            to = columnId(to, "to");
            if (from.equals(to)) {
                throw new IllegalArgumentException(
                        "a manual relationship needs two different columns: " + from);
            }
        }

        /** Validates one end of the relationship and returns it lower-cased. */
        private static String columnId(String value, String what) {
            Objects.requireNonNull(value, what + " must not be null");
            GraphIds.ColumnId parts = GraphIds.parseColumnId(value);
            return GraphIds.columnId(parts.source(), parts.entity(), parts.column());
        }
    }

    /** Copies the label entries, with every id lower-cased. */
    private static Map<String, Label> normalizeLabels(Map<String, Label> labels) {
        Map<String, Label> normalized = new LinkedHashMap<>();
        if (labels == null) {
            return normalized;
        }
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            Objects.requireNonNull(entry.getValue(), "label entry must not be null: "
                    + entry.getKey());
            normalized.put(idKey(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    /** Copies the decisions, validating every id and value. */
    private static Map<String, String> normalizeDecisions(Map<String, String> decisions) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (decisions == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : decisions.entrySet()) {
            String id = idKey(entry.getKey());
            GraphIds.parseRelationshipId(id);
            normalized.put(id, decisionValue(entry.getValue()));
        }
        return normalized;
    }

    /** Copies the manual relationships, dropping repeated pairs. */
    private static List<ManualRelationship> normalizeManualRelationships(
            List<ManualRelationship> manualRelationships) {
        List<ManualRelationship> normalized = new ArrayList<>();
        if (manualRelationships == null) {
            return normalized;
        }
        for (ManualRelationship entry : manualRelationships) {
            Objects.requireNonNull(entry, "manual relationship must not be null");
            if (!normalized.contains(entry)) {
                normalized.add(entry);
            }
        }
        return normalized;
    }

    /** The stored word for a review decision, or an exception for anything else. */
    private static String decisionValue(String status) {
        String trimmed = Objects.requireNonNull(status, "decision must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!GraphRelationship.CONFIRMED.equals(trimmed)
                && !GraphRelationship.REJECTED.equals(trimmed)) {
            throw new IllegalArgumentException(
                    "decision must be \"" + GraphRelationship.CONFIRMED + "\" or \""
                            + GraphRelationship.REJECTED + "\": " + trimmed);
        }
        return trimmed;
    }

    /** Normalizes an entity, column or relationship id: trimmed, lower-cased. */
    private static String idKey(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** A value that must be present and at most {@code max} characters long. */
    private static String required(String value, String what, int max) {
        String trimmed = optional(value, what, max);
        if (trimmed == null) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return trimmed;
    }

    /**
     * A value that may be absent and, when present, is trimmed to at most
     * {@code max} characters; blank becomes null.
     */
    private static String optional(String value, String what, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    what + " must be at most " + max + " characters: was "
                            + trimmed.length());
        }
        return trimmed;
    }
}
