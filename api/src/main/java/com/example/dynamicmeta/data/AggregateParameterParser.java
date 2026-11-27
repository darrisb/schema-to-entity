package com.example.dynamicmeta.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Parses aggregate endpoint parameters against a cached entity schema. */
public final class AggregateParameterParser {

    private static final String GROUP = "group";
    private static final String METRIC = "metric";
    private static final String SORT = "sort";

    private final QueryParameterParser filterParser;

    public AggregateParameterParser(QueryParameterParser filterParser) {
        this.filterParser = Objects.requireNonNull(filterParser, "filterParser");
    }

    public AggregateSpec parse(EntityModel entity, Map<String, List<String>> parameters) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(parameters, "parameters");

        Map<String, List<String>> filters = new LinkedHashMap<>(parameters);
        List<String> groups = splitCsv(filters.remove(GROUP));
        List<String> metricTokens = splitCsv(filters.remove(METRIC));
        String sortToken = optionalSingle(SORT, filters.remove(SORT));

        QuerySpec query = filterParser.parse(entity, filters);
        if (!query.fields().isEmpty() || query.offset() != null || !query.sorts().isEmpty()) {
            throw new IllegalArgumentException("Aggregate queries do not support fields or offset");
        }

        List<String> resolvedGroups = groups.stream()
                .map(group -> resolveColumn(entity, group).name()).toList();
        List<AggregateSpec.Metric> metrics = new ArrayList<>(metricTokens.size());
        for (String token : metricTokens) {
            metrics.add(parseMetric(entity, token));
        }

        return new AggregateSpec(resolvedGroups, metrics, query.equals(), query.notEquals(),
                query.contains(), query.nullChecks(), query.ranges(), parseSort(sortToken),
                query.limit());
    }

    private static AggregateSpec.Metric parseMetric(EntityModel entity, String token) {
        String[] parts = token.split(":", -1);
        AggregateSpec.Function function;
        try {
            function = AggregateSpec.Function.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid metric '" + token + "'");
        }
        if (function == AggregateSpec.Function.COUNT && parts.length == 1) {
            return new AggregateSpec.Metric(function, null);
        }
        if (parts.length != 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid metric '" + token + "'");
        }
        return new AggregateSpec.Metric(function, resolveColumn(entity, parts[1]).name());
    }

    private static AggregateSpec.Sort parseSort(String token) {
        if (token == null) return null;
        String column = token;
        QuerySpec.Direction direction = QuerySpec.Direction.ASC;
        if (token.startsWith("-")) {
            direction = QuerySpec.Direction.DESC;
            column = token.substring(1);
        } else if (token.startsWith("+")) {
            column = token.substring(1);
        }
        if (column.isBlank()) {
            throw new IllegalArgumentException("Invalid aggregate sort");
        }
        return new AggregateSpec.Sort(column, direction);
    }

    private static ColumnModel resolveColumn(EntityModel entity, String requested) {
        for (ColumnModel column : entity.columns()) {
            if (column.name().equalsIgnoreCase(requested)) return column;
        }
        throw new IllegalArgumentException(
                "Unknown column '" + requested + "' for entity '" + entity.name() + "'");
    }

    private static List<String> splitCsv(List<String> values) {
        if (values == null) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            for (String token : value.split(",")) {
                if (!token.trim().isEmpty()) tokens.add(token.trim());
            }
        }
        return tokens;
    }

    private static String optionalSingle(String name, List<String> values) {
        if (values == null) return null;
        if (values.size() != 1 || values.getFirst() == null) {
            throw new IllegalArgumentException(
                    "Query parameter '" + name + "' may be given only once");
        }
        return values.getFirst().trim();
    }
}
