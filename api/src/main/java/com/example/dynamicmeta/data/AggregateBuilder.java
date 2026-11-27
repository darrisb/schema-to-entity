package com.example.dynamicmeta.data;

import com.example.dynamicmeta.connection.DatabaseType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure builder for safe, read-only grouped aggregate SELECT statements.
 * Metric aliases are {@code count} for {@code COUNT(*)} and
 * {@code <function>_<column>} for column metrics, including
 * {@code count_<column>} for {@code COUNT(column)}.
 */
public final class AggregateBuilder {

    private final SelectBuilder sql;

    public AggregateBuilder(DatabaseType databaseType, String catalog, String schema) {
        sql = new SelectBuilder(databaseType, catalog, schema);
    }

    public AggregateBuilder(DatabaseType databaseType, String schema) {
        this(databaseType, null, schema);
    }

    public AggregateBuilder(DatabaseType databaseType) {
        this(databaseType, null, null);
    }

    public SqlStatement aggregate(EntityModel entity, AggregateSpec spec) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(spec, "spec");

        List<String> groups = spec.groupBy().stream()
                .map(column -> sql.resolveColumn(entity, column)).toList();
        List<ResolvedMetric> metrics = spec.metrics().stream()
                .map(metric -> resolveMetric(entity, metric)).toList();
        ensureUniqueOutputNames(groups, metrics);

        List<String> selections = new ArrayList<>();
        groups.stream().map(sql::quote).forEach(selections::add);
        metrics.stream().map(this::metricSql).forEach(selections::add);

        List<Object> parameters = new ArrayList<>();
        StringBuilder statement = new StringBuilder("SELECT ")
                .append(String.join(", ", selections))
                .append(" FROM ").append(sql.qualifiedTable(entity));
        sql.appendWhere(statement, entity, spec.equals(), spec.notEquals(), spec.contains(),
                spec.nullChecks(), spec.ranges(), parameters);
        if (!groups.isEmpty()) {
            statement.append(" GROUP BY ")
                    .append(String.join(", ", groups.stream().map(sql::quote).toList()));
        }
        appendOrderBy(statement, groups, metrics, spec.sort());
        sql.appendPaging(statement, spec.limit(), null, parameters);
        return new SqlStatement(statement.toString(), parameters);
    }

    /**
     * Returns the metric aliases produced by {@link #aggregate(EntityModel, AggregateSpec)},
     * resolved against the entity's discovered column spelling.
     */
    public List<String> metricAliases(EntityModel entity, AggregateSpec spec) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(spec, "spec");

        List<String> groups = spec.groupBy().stream()
                .map(column -> sql.resolveColumn(entity, column)).toList();
        List<ResolvedMetric> metrics = spec.metrics().stream()
                .map(metric -> resolveMetric(entity, metric)).toList();
        ensureUniqueOutputNames(groups, metrics);
        return metrics.stream().map(ResolvedMetric::alias).toList();
    }

    private ResolvedMetric resolveMetric(EntityModel entity, AggregateSpec.Metric metric) {
        if (metric.function() == AggregateSpec.Function.COUNT && metric.column() == null) {
            return new ResolvedMetric(metric.function(), null, "count");
        }
        String column = sql.resolveColumn(entity, metric.column());
        ColumnModel model = entity.columns().stream()
                .filter(candidate -> candidate.name().equals(column)).findFirst().orElseThrow();
        boolean allowed = switch (metric.function()) {
            case COUNT -> true;
            case SUM, AVG -> model.category() == ColumnCategory.NUMBER;
            case MIN, MAX -> model.category() == ColumnCategory.NUMBER
                    || model.category() == ColumnCategory.DATE
                    || model.category() == ColumnCategory.TIMESTAMP;
        };
        if (!allowed) {
            throw new IllegalArgumentException(metric.function().name().toLowerCase(Locale.ROOT)
                    + " is not allowed for " + model.category() + " column '" + column + "'");
        }
        String alias = metric.function().name().toLowerCase(Locale.ROOT) + "_"
                + column.toLowerCase(Locale.ROOT);
        return new ResolvedMetric(metric.function(), column, alias);
    }

    private String metricSql(ResolvedMetric metric) {
        String argument = metric.column() == null ? "*" : sql.quote(metric.column());
        return metric.function().name() + "(" + argument + ") AS " + sql.quote(metric.alias());
    }

    private void appendOrderBy(StringBuilder statement, List<String> groups,
                               List<ResolvedMetric> metrics, AggregateSpec.Sort requested) {
        if (requested == null) {
            ResolvedMetric count = metrics.stream()
                    .filter(metric -> metric.alias().equalsIgnoreCase("count"))
                    .findFirst().orElse(null);
            if (count != null) {
                appendOrderBy(statement, count.alias(), QuerySpec.Direction.DESC);
            } else if (!metrics.isEmpty()) {
                appendOrderBy(statement, metrics.getFirst().alias(), QuerySpec.Direction.DESC);
            } else if (!groups.isEmpty()) {
                appendOrderBy(statement, groups.getFirst(), QuerySpec.Direction.ASC);
            }
            return;
        }

        String name = requested.column();
        String resolved = groups.stream().filter(group -> group.equalsIgnoreCase(name)).findFirst()
                .orElseGet(() -> metrics.stream().map(ResolvedMetric::alias)
                        .filter(alias -> alias.equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown aggregate sort column '" + name + "'")));
        appendOrderBy(statement, resolved, requested.effectiveDirection());
    }

    private void appendOrderBy(StringBuilder statement, String column,
                               QuerySpec.Direction direction) {
        statement.append(" ORDER BY ").append(sql.quote(column))
                .append(direction == QuerySpec.Direction.DESC ? " DESC" : " ASC");
    }

    private static void ensureUniqueOutputNames(List<String> groups,
                                                List<ResolvedMetric> metrics) {
        Set<String> aliases = new HashSet<>();
        for (ResolvedMetric metric : metrics) {
            String normalizedAlias = metric.alias().toLowerCase(Locale.ROOT);
            if (!aliases.add(normalizedAlias)) {
                throw new IllegalArgumentException("Duplicate metric alias '" + metric.alias() + "'");
            }
            groups.stream().filter(group -> group.equalsIgnoreCase(metric.alias())).findFirst()
                    .ifPresent(group -> {
                        throw new IllegalArgumentException("Metric alias '" + metric.alias()
                                + "' conflicts with group column '" + group + "'");
                    });
        }
    }

    private record ResolvedMetric(AggregateSpec.Function function, String column, String alias) {}
}
