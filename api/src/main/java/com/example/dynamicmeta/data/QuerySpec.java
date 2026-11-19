package com.example.dynamicmeta.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, caller-validated description of one read-only query.
 * <p>
 * {@code fields} selects columns; an empty list means every column of the
 * entity. {@code equals} and {@code contains} are filters keyed by column
 * name. {@code sorts} is the optional ordering, in priority order; an empty
 * list means no ordering. {@code limit} and {@code offset} are optional
 * paging values; a null value means "not set".
 * <p>
 * The data API is read-only: a query spec never describes INSERT, UPDATE,
 * DELETE or DDL. Values are always passed to the database as bind
 * parameters; only column names come from the caller (and are resolved
 * against the {@link EntityModel} by the SQL builder).
 *
 * @param fields   requested columns in request order; empty means all columns
 * @param equals   exact-match filters, column name to value
 * @param contains case-insensitive substring filters, column name to text
 * @param sorts    optional ordering in priority order; empty means no ordering
 * @param limit    optional maximum number of rows
 * @param offset   optional number of rows to skip
 */
public record QuerySpec(
        List<String> fields,
        Map<String, Object> equals,
        Map<String, String> contains,
        List<Sort> sorts,
        Integer limit,
        Integer offset) {

    public QuerySpec {
        fields = fields == null ? List.of() : List.copyOf(fields);
        equals = copyOf(equals);
        contains = copyOf(contains);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }

    /**
     * Convenience constructor for a single sort column.
     *
     * @param sort one sort column and direction, or null for no ordering
     */
    public QuerySpec(List<String> fields, Map<String, Object> equals,
                     Map<String, String> contains, Sort sort,
                     Integer limit, Integer offset) {
        this(fields, equals, contains, sort == null ? List.of() : List.of(sort),
                limit, offset);
    }

    /** Returns true when a limit or an offset was requested. */
    public boolean hasPaging() {
        return limit != null || offset != null;
    }

    /**
     * Returns the first sort column, or null when no ordering was requested.
     * Callers that support only one sort may use this; the full ordering is
     * {@link #sorts()}.
     */
    public Sort sort() {
        return sorts.isEmpty() ? null : sorts.get(0);
    }

    /**
     * Sort column and direction. A null direction means ascending.
     *
     * @param column    column to sort by
     * @param direction {@link Direction#ASC} or {@link Direction#DESC}
     */
    public record Sort(String column, Direction direction) {

        public Sort {
            column = Objects.requireNonNull(column, "sort column");
        }

        /** Returns the requested direction, defaulting to ascending. */
        public Direction effectiveDirection() {
            return direction == null ? Direction.ASC : direction;
        }
    }

    /** Sort direction. */
    public enum Direction {
        ASC, DESC
    }

    private static <V> Map<String, V> copyOf(Map<String, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "column name"),
                Objects.requireNonNull(value, "filter value")));
        return Collections.unmodifiableMap(copy);
    }
}
