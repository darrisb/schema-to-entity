package com.example.dynamicmeta.drift;

import java.util.Objects;

/**
 * One classified difference between a baseline {@link SchemaSnapshot} and the
 * current one, as produced by {@link SnapshotDiff}.
 * <p>
 * Each change says what happened ({@link #type}), how much it matters
 * ({@link #severity}) and which object it happened to: {@link #entity} is
 * always the table or view the change belongs to, {@link #column} names the
 * column for column-level changes and is {@code null} for entity-level ones.
 * {@link #before} and {@link #after} carry the old and new state in a short
 * human-readable form — the kind of an entity, a column's type, its
 * nullability, the list of key columns, the target of a foreign key — and are
 * {@code null} when there is no such state, as for a change that only adds or
 * only removes something.
 * <p>
 * A rename is never certain from metadata alone, so it is only ever reported
 * as a candidate: {@link Type#COLUMN_RENAME_CANDIDATE} and
 * {@link Type#ENTITY_RENAME_CANDIDATE} come alongside, rather than instead of,
 * the removal and the addition they pair up, and {@link #relatedTo} names the
 * other column or entity so a reader can see what is matched with what. Every
 * other change leaves {@link #relatedTo} {@code null}.
 * <p>
 * This is a value object only: nothing in it reads from or writes to any
 * source database.
 *
 * @param type      what changed
 * @param severity  how much the change matters
 * @param entity    entity the change belongs to, spelled as the baseline spelled it
 * @param column    column the change belongs to, or {@code null} for entity-level changes
 * @param before    state in the baseline, or {@code null} when there was none
 * @param after     state now, or {@code null} when there is none
 * @param relatedTo the column or entity this candidate pairs with, {@code null} otherwise
 */
public record SchemaChange(Type type, Severity severity, String entity, String column,
                           String before, String after, String relatedTo) {

    public SchemaChange {
        type = Objects.requireNonNull(type, "type");
        severity = Objects.requireNonNull(severity, "severity");
        entity = Objects.requireNonNull(entity, "entity");
    }

    /** The kind of difference a {@link SchemaChange} describes. */
    public enum Type {

        /** A table or view the baseline did not have. */
        ENTITY_ADDED,
        /** A table or view that is gone. */
        ENTITY_REMOVED,
        /** A table that is now a view, or a view that is now a table. */
        ENTITY_KIND_CHANGED,
        /** A removed entity whose columns mostly match an added entity's. */
        ENTITY_RENAME_CANDIDATE,
        /** A column the baseline did not have. */
        COLUMN_ADDED,
        /** A column that is gone. */
        COLUMN_REMOVED,
        /** A column reported with a different type. */
        COLUMN_TYPE_CHANGED,
        /** A column that changed between NULL allowed and NULL forbidden. */
        COLUMN_NULLABILITY_CHANGED,
        /** A removed column whose type matches an added column of a similar name. */
        COLUMN_RENAME_CANDIDATE,
        /** The entity's key columns, or their order, are different. */
        KEY_COLUMNS_CHANGED,
        /** A declared foreign key the baseline did not have. */
        FOREIGN_KEY_ADDED,
        /** A declared foreign key that is gone. */
        FOREIGN_KEY_REMOVED
    }

    /** How much a {@link SchemaChange} matters. */
    public enum Severity {

        /** The schema gained something; existing data and readers are unaffected. */
        ADDITIVE,
        /** Something existing consumers rely on is gone or tightened. */
        BREAKING,
        /** Not safe to classify on metadata alone: a human should look. */
        REVIEW
    }
}
