package com.example.dynamicmeta.data;

import com.example.dynamicmeta.connection.DatabaseType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pure builder for safe, read-only grouped aggregate SELECT statements. */
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
        ensureUniqueAliases(metrics);

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
        String name = requested == null ? "count" : requested.column();
        QuerySpec.Direction direction = requested == null
                ? QuerySpec.Direction.DESC : requested.effectiveDirection();
        String resolved = groups.stream().filter(group -> group.equalsIgnoreCase(name)).findFirst()
                .orElseGet(() -> metrics.stream().map(ResolvedMetric::alias)
                        .filter(alias -> alias.equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown aggregate sort column '" + name + "'")));
        statement.append(" ORDER BY ").append(sql.quote(resolved))
                .append(direction == QuerySpec.Direction.DESC ? " DESC" : " ASC");
    }

    private static void ensureUniqueAliases(List<ResolvedMetric> metrics) {
        Set<String> aliases = new HashSet<>();
        for (ResolvedMetric metric : metrics) {
            if (!aliases.add(metric.alias())) {
                throw new IllegalArgumentException("Duplicate metric alias '" + metric.alias() + "'");
            }
        }
    }

    private record ResolvedMetric(AggregateSpec.Function function, String column, String alias) {}
}
