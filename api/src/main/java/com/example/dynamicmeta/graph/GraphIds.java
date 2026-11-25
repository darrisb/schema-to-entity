package com.example.dynamicmeta.graph;

import java.util.Locale;
import java.util.Objects;

/**
 * Builds and parses the stable, lower-case identifiers used inside a
 * {@link SchemaGraph}.
 * <p>
 * Every identifier is built from the parts that name a thing inside the
 * combined document:
 *
 * <pre>
 * entity id       = source:entity
 * column id       = source:entity.column
 * relationship id = fromColumnId-&gt;toColumnId
 * </pre>
 *
 * Each builder lower-cases the whole result with {@link Locale#ROOT}, so an
 * id is stable no matter how a database reports the casing of its names. The
 * parse helpers split on the separators and return the lower-cased parts.
 * <p>
 * Source, entity and column names must not contain the separator characters
 * ({@code :} and {@code .}); names reported by JDBC metadata never do. The
 * builders reject them because they would make the ids ambiguous.
 */
public final class GraphIds {

    private static final String ENTITY_SEPARATOR = ":";
    private static final String COLUMN_SEPARATOR = ".";
    private static final String RELATIONSHIP_SEPARATOR = "->";

    private GraphIds() {
    }

    /** Returns {@code source:entity}, lower-cased. */
    public static String entityId(String source, String entity) {
        return part(source, "source") + ENTITY_SEPARATOR + part(entity, "entity");
    }

    /** Returns {@code source:entity.column}, lower-cased. */
    public static String columnId(String source, String entity, String column) {
        return part(source, "source") + ENTITY_SEPARATOR + part(entity, "entity")
                + COLUMN_SEPARATOR + part(column, "column");
    }

    /**
     * Returns {@code fromColumnId->toColumnId}, lower-cased. The result is
     * deterministic: the same pair always produces the same id, whichever of
     * the two ends is written first is the {@code from} end.
     */
    public static String relationshipId(String fromColumnId, String toColumnId) {
        return idPart(fromColumnId, "fromColumnId") + RELATIONSHIP_SEPARATOR
                + idPart(toColumnId, "toColumnId");
    }

    /** Splits an entity id built by {@link #entityId} into its parts. */
    public static EntityId parseEntityId(String id) {
        String lower = wholeId(id);
        int separator = lower.indexOf(ENTITY_SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException(
                    "entity id must look like source:entity: " + lower);
        }
        String source = lower.substring(0, separator);
        String entity = lower.substring(separator + ENTITY_SEPARATOR.length());
        if (source.isEmpty() || entity.isEmpty()) {
            throw new IllegalArgumentException(
                    "entity id must look like source:entity: " + lower);
        }
        return new EntityId(source, entity);
    }

    /** Splits a column id built by {@link #columnId} into its parts. */
    public static ColumnId parseColumnId(String id) {
        String lower = wholeId(id);
        int entitySeparator = lower.indexOf(ENTITY_SEPARATOR);
        int columnSeparator = lower.indexOf(COLUMN_SEPARATOR);
        if (entitySeparator < 0 || columnSeparator < entitySeparator) {
            throw new IllegalArgumentException(
                    "column id must look like source:entity.column: " + lower);
        }
        String source = lower.substring(0, entitySeparator);
        String entity = lower.substring(entitySeparator + ENTITY_SEPARATOR.length(),
                columnSeparator);
        String column = lower.substring(columnSeparator + COLUMN_SEPARATOR.length());
        if (source.isEmpty() || entity.isEmpty() || column.isEmpty()) {
            throw new IllegalArgumentException(
                    "column id must look like source:entity.column: " + lower);
        }
        return new ColumnId(source, entity, column);
    }

    /** Splits a relationship id built by {@link #relationshipId} into its parts. */
    public static RelationshipId parseRelationshipId(String id) {
        String lower = wholeId(id);
        int separator = lower.indexOf(RELATIONSHIP_SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException(
                    "relationship id must look like fromColumnId->toColumnId: " + lower);
        }
        String from = lower.substring(0, separator);
        String to = lower.substring(separator + RELATIONSHIP_SEPARATOR.length());
        if (from.isEmpty() || to.isEmpty()) {
            throw new IllegalArgumentException(
                    "relationship id must look like fromColumnId->toColumnId: " + lower);
        }
        return new RelationshipId(from, to);
    }

    /**
     * One lower-cased name of a source, entity or column inside the document.
     *
     * @param source lower-cased source id
     * @param entity lower-cased entity name
     */
    public record EntityId(String source, String entity) {
    }

    /**
     * The lower-cased names a column id is built from.
     *
     * @param source lower-cased source id
     * @param entity lower-cased entity name
     * @param column lower-cased column name
     */
    public record ColumnId(String source, String entity, String column) {
    }

    /**
     * The lower-cased column ids at the two ends of a relationship.
     *
     * @param from column id the relationship starts at
     * @param to   column id the relationship points at
     */
    public record RelationshipId(String from, String to) {
    }

    /**
     * Normalizes one source, entity or column name: trimmed, lower-cased and
     * checked against the separator characters.
     */
    private static String part(String value, String what) {
        String trimmed = nonBlank(value, what);
        if (trimmed.indexOf(':') >= 0 || trimmed.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    what + " must not contain ':' or '.': " + trimmed);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** Normalizes one column id used as a relationship end. */
    private static String idPart(String value, String what) {
        String trimmed = nonBlank(value, what);
        if (trimmed.contains(RELATIONSHIP_SEPARATOR)) {
            throw new IllegalArgumentException(what + " must not contain \"->\": " + trimmed);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** Normalizes a whole id before splitting it: trimmed and lower-cased. */
    private static String wholeId(String id) {
        return nonBlank(id, "id").toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return trimmed;
    }
}
