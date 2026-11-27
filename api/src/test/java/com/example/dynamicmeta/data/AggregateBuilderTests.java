package com.example.dynamicmeta.data;

import com.example.dynamicmeta.connection.DatabaseType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateBuilderTests {

    private static final EntityModel ORDERS = new EntityModel("ORDERS", "table", List.of(
            new ColumnModel("STATUS", "varchar(20)", ColumnCategory.TEXT, false),
            new ColumnModel("TOTAL", "numeric(12,2)", ColumnCategory.NUMBER, false),
            new ColumnModel("CREATED_AT", "timestamp", ColumnCategory.TIMESTAMP, false),
            new ColumnModel("REGION", "varchar(20)", ColumnCategory.TEXT, true),
            new ColumnModel("CHANNEL", "varchar(20)", ColumnCategory.TEXT, true)), List.of());

    @Test
    void countGroupedByStatusUsesDialectQuotingAndPaging() {
        AggregateSpec spec = spec(List.of("status"),
                List.of(new AggregateSpec.Metric(AggregateSpec.Function.COUNT, null)),
                List.of(), null, 25);

        assertThat(new AggregateBuilder(DatabaseType.POSTGRESQL, "public")
                .aggregate(ORDERS, spec).sql()).isEqualTo(
                "SELECT \"STATUS\", COUNT(*) AS \"count\" FROM \"public\".\"ORDERS\""
                        + " GROUP BY \"STATUS\" ORDER BY \"count\" DESC LIMIT ?");

        SqlStatement sqlServer = new AggregateBuilder(DatabaseType.SQLSERVER, "sales", "dbo")
                .aggregate(ORDERS, spec);
        assertThat(sqlServer.sql()).isEqualTo(
                "SELECT [STATUS], COUNT(*) AS [count] FROM [sales].[dbo].[ORDERS]"
                        + " GROUP BY [STATUS] ORDER BY [count] DESC"
                        + " OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(sqlServer.parameters()).containsExactly(25);
    }

    @Test
    void sumAndAverageWithRangeFiltersBindValuesInOrder() {
        AggregateSpec spec = spec(List.of("status"), List.of(
                new AggregateSpec.Metric(AggregateSpec.Function.SUM, "total"),
                new AggregateSpec.Metric(AggregateSpec.Function.AVG, "total")),
                List.of(
                        new QuerySpec.RangeFilter("total", QuerySpec.RangeOperator.GTE,
                                new BigDecimal("10.00")),
                        new QuerySpec.RangeFilter("created_at", QuerySpec.RangeOperator.LT,
                                "2026-01-01T00:00:00")),
                new AggregateSpec.Sort("sum_total", QuerySpec.Direction.DESC), null);

        SqlStatement statement = new AggregateBuilder(DatabaseType.POSTGRESQL, "public")
                .aggregate(ORDERS, spec);

        assertThat(statement.sql()).isEqualTo(
                "SELECT \"STATUS\", SUM(\"TOTAL\") AS \"sum_total\","
                        + " AVG(\"TOTAL\") AS \"avg_total\" FROM \"public\".\"ORDERS\""
                        + " WHERE \"TOTAL\" >= ? AND \"CREATED_AT\" < ?"
                        + " GROUP BY \"STATUS\" ORDER BY \"sum_total\" DESC");
        assertThat(statement.parameters()).containsExactly(
                new BigDecimal("10.00"), "2026-01-01T00:00:00");
    }

    @Test
    void sumOnlyWithoutSortOrdersByFirstMetricDescending() {
        AggregateSpec spec = spec(List.of("status"),
                List.of(new AggregateSpec.Metric(AggregateSpec.Function.SUM, "total")),
                List.of(), null, null);

        assertThat(new AggregateBuilder(DatabaseType.POSTGRESQL, "public")
                .aggregate(ORDERS, spec).sql()).isEqualTo(
                "SELECT \"STATUS\", SUM(\"TOTAL\") AS \"sum_total\""
                        + " FROM \"public\".\"ORDERS\" GROUP BY \"STATUS\""
                        + " ORDER BY \"sum_total\" DESC");
    }

    @Test
    void countColumnWithoutSortOrdersByItsAliasDescending() {
        AggregateSpec spec = spec(List.of("status"),
                List.of(new AggregateSpec.Metric(AggregateSpec.Function.COUNT, "region")),
                List.of(), null, null);

        assertThat(new AggregateBuilder(DatabaseType.POSTGRESQL, "public")
                .aggregate(ORDERS, spec).sql()).isEqualTo(
                "SELECT \"STATUS\", COUNT(\"REGION\") AS \"count_region\""
                        + " FROM \"public\".\"ORDERS\" GROUP BY \"STATUS\""
                        + " ORDER BY \"count_region\" DESC");
    }

    @Test
    void rejectsMetricAliasMatchingGroupColumnIgnoringCase() {
        EntityModel entity = new EntityModel("TOTALS", "table", List.of(
                new ColumnModel("SUM_TOTAL", "varchar(20)", ColumnCategory.TEXT, false),
                new ColumnModel("TOTAL", "numeric(12,2)", ColumnCategory.NUMBER, false)),
                List.of());
        AggregateSpec spec = spec(List.of("SUM_TOTAL"),
                List.of(new AggregateSpec.Metric(AggregateSpec.Function.SUM, "TOTAL")),
                List.of(), null, null);

        assertThatThrownBy(() -> new AggregateBuilder(DatabaseType.POSTGRESQL)
                .aggregate(entity, spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum_total")
                .hasMessageContaining("SUM_TOTAL");
    }

    @Test
    void rejectsSumOnTextAndFourthGroupColumn() {
        assertThatThrownBy(() -> new AggregateBuilder(DatabaseType.POSTGRESQL)
                .aggregate(ORDERS, spec(List.of("status"),
                        List.of(new AggregateSpec.Metric(AggregateSpec.Function.SUM, "status")),
                        List.of(), new AggregateSpec.Sort("sum_status", null), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");

        assertThatThrownBy(() -> spec(List.of("status", "region", "channel", "total"),
                List.of(new AggregateSpec.Metric(AggregateSpec.Function.COUNT, null)),
                List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At most 3");
    }

    private static AggregateSpec spec(List<String> groups, List<AggregateSpec.Metric> metrics,
                                      List<QuerySpec.RangeFilter> ranges,
                                      AggregateSpec.Sort sort, Integer limit) {
        return new AggregateSpec(groups, metrics, Map.of(), Map.of(), Map.of(), Map.of(),
                ranges, sort, limit);
    }
}
