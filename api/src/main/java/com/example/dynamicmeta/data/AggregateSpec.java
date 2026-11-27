package com.example.dynamicmeta.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable description of one read-only grouped aggregate query. */
public record AggregateSpec(
        List<String> groupBy,
        List<Metric> metrics,
        Map<String, Object> equals,
        Map<String, Object> notEquals,
        Map<String, String> contains,
        Map<String, Boolean> nullChecks,
        List<QuerySpec.RangeFilter> ranges,
        Sort sort,
        Integer limit) {

    public AggregateSpec {
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        equals = copyOf(equals);
        notEquals = copyOf(notEquals);
        contains = copyOf(contains);
        nullChecks = copyOf(nullChecks);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
        if (groupBy.size() > 3) {
            throw new IllegalArgumentException("At most 3 group columns are allowed");
        }
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("At least one metric is required");
        }
        if (limit != null && (limit < 1 || limit > 500)) {
            throw new IllegalArgumentException("Aggregate limit must be between 1 and 500");
        }
    }

    public record Metric(Function function, String column) {
        public Metric {
            function = Objects.requireNonNull(function, "metric function");
            if (function != Function.COUNT && (column == null || column.isBlank())) {
                throw new IllegalArgumentException(function.name().toLowerCase()
                        + " requires a column");
            }
        }
    }

    public record Sort(String column, QuerySpec.Direction direction) {
        public Sort {
            column = Objects.requireNonNull(column, "sort column");
        }

        public QuerySpec.Direction effectiveDirection() {
            return direction == null ? QuerySpec.Direction.ASC : direction;
        }
    }

    public enum Function { COUNT, SUM, AVG, MIN, MAX }

    private static <V> Map<String, V> copyOf(Map<String, V> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "column name"),
                Objects.requireNonNull(value, "filter value")));
        return Collections.unmodifiableMap(copy);
    }
}
