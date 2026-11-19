package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaToEntityProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DA-005: HTTP query parameters become a validated, read-only QuerySpec.
 */
class QueryParameterParserTests {

    private static final EntityModel PRODUCTS = new EntityModel(
            "PRODUCTS",
            "table",
            List.of(
                    new ColumnModel("ID", "bigint", ColumnCategory.NUMBER, false),
                    new ColumnModel("NAME", "varchar(80)", ColumnCategory.TEXT, false),
                    new ColumnModel("PRICE", "numeric(10,2)", ColumnCategory.NUMBER, false),
                    new ColumnModel("ACTIVE", "boolean", ColumnCategory.BOOLEAN, false),
                    new ColumnModel("RELEASED_ON", "date", ColumnCategory.DATE, true),
                    new ColumnModel("CREATED_AT", "timestamp", ColumnCategory.TIMESTAMP, true),
                    new ColumnModel("NOTES", "text", ColumnCategory.TEXT, true),
                    new ColumnModel("LIMIT", "bigint", ColumnCategory.NUMBER, true),
                    new ColumnModel("RAW", "jsonb", ColumnCategory.OTHER, true)),
            List.of("ID"));

    @Test
    void fieldsSelectsColumnsInRequestOrder() {
        QuerySpec spec = parser().parse(PRODUCTS, params("fields", "name,id"));

        assertThat(spec.fields()).containsExactly("NAME", "ID");
    }

    @Test
    void omittedOrBlankFieldsMeansEveryColumn() {
        assertThat(parser().parse(PRODUCTS, Map.of()).fields()).isEmpty();
        assertThat(parser().parse(PRODUCTS, params("fields", "")).fields()).isEmpty();
        assertThat(parser().parse(PRODUCTS, params("fields", " , ")).fields()).isEmpty();
    }

    @Test
    void sortParsesAscendingThenDescending() {
        QuerySpec spec = parser().parse(PRODUCTS, params("sort", "name,-id"));

        assertThat(spec.sorts()).containsExactly(
                new QuerySpec.Sort("NAME", QuerySpec.Direction.ASC),
                new QuerySpec.Sort("ID", QuerySpec.Direction.DESC));
    }

    @Test
    void sortAcceptsExplicitAscendingPrefix() {
        QuerySpec spec = parser().parse(PRODUCTS, params("sort", "+name,id"));

        assertThat(spec.sorts()).containsExactly(
                new QuerySpec.Sort("NAME", QuerySpec.Direction.ASC),
                new QuerySpec.Sort("ID", QuerySpec.Direction.ASC));
    }

    @Test
    void limitDefaultsToConfiguredDefaultAndOffsetStaysUnset() {
        QueryParameterParser parser = new QueryParameterParser(25, 100);

        QuerySpec spec = parser.parse(PRODUCTS, Map.of());

        assertThat(spec.limit()).isEqualTo(25);
        assertThat(spec.offset()).isNull();
        assertThat(spec.hasPaging()).isTrue();
    }

    @Test
    void explicitLimitAndOffsetAreKept() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "limit", List.of("10"),
                "offset", List.of("50")));

        assertThat(spec.limit()).isEqualTo(10);
        assertThat(spec.offset()).isEqualTo(50);
    }

    @Test
    void limitAndOffsetBoundsAreAccepted() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "limit", List.of("1"),
                "offset", List.of("0")));
        assertThat(spec.limit()).isEqualTo(1);
        assertThat(spec.offset()).isZero();

        QuerySpec maxed = parser().parse(PRODUCTS, Map.of(
                "limit", List.of("200"),
                "offset", List.of("1000000")));
        assertThat(maxed.limit()).isEqualTo(200);
        assertThat(maxed.offset()).isEqualTo(1_000_000);
    }

    @Test
    void limitAboveMaxIsRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("limit", "201")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void limitBelowOneAndNonNumericAreRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("limit", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("limit", "many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void offsetOutOfRangeAndNonNumericAreRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("offset", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("offset", "1000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("offset", "soon")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }

    @Test
    void equalsAndContainsFiltersParse() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "eq.price", List.of("19.99"),
                "contains.name", List.of("phone")));

        assertThat(spec.equals()).containsEntry("PRICE", new BigDecimal("19.99"));
        assertThat(spec.contains()).containsEntry("NAME", "phone");
    }

    @Test
    void columnNamesMatchCaseInsensitivelyAndResolveToModelSpelling() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "fields", List.of("id"),
                "sort", List.of("-Name"),
                "eq.PRICE", List.of("1.5"),
                "contains.notes", List.of("x")));

        assertThat(spec.fields()).containsExactly("ID");
        assertThat(spec.sorts()).containsExactly(
                new QuerySpec.Sort("NAME", QuerySpec.Direction.DESC));
        assertThat(spec.equals()).containsOnlyKeys("PRICE");
        assertThat(spec.contains()).containsOnlyKeys("NOTES");
    }

    @Test
    void columnNamedLikeAReservedParameterIsReachableThroughThePrefix() {
        QuerySpec spec = parser().parse(PRODUCTS, params("eq.limit", "5"));

        assertThat(spec.equals()).containsEntry("LIMIT", new BigDecimal("5"));
    }

    @Test
    void valuesConvertByCategory() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "eq.id", List.of("42"),
                "eq.active", List.of("true"),
                "eq.released_on", List.of("2026-01-15"),
                "eq.created_at", List.of("2026-01-15T10:30:00Z"),
                "eq.name", List.of("  kept as-is  ")));

        assertThat(spec.equals().get("ID")).isEqualTo(new BigDecimal("42"));
        assertThat(spec.equals().get("ACTIVE")).isEqualTo(Boolean.TRUE);
        assertThat(spec.equals().get("RELEASED_ON")).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(spec.equals().get("CREATED_AT"))
                .isEqualTo(Instant.parse("2026-01-15T10:30:00Z"));
        assertThat(spec.equals().get("NAME")).isEqualTo("  kept as-is  ");
    }

    @Test
    void localTimestampWithoutOffsetBecomesLocalDateTime() {
        QuerySpec spec = parser().parse(PRODUCTS, params("eq.created_at", "2026-01-15T10:30:00"));

        assertThat(spec.equals().get("CREATED_AT"))
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 30));
    }

    @Test
    void booleanAcceptsTrueAndFalse() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "eq.active", List.of("false")));

        assertThat(spec.equals().get("ACTIVE")).isEqualTo(Boolean.FALSE);
        assertThat(parser().parse(PRODUCTS, params("eq.active", "TRUE"))
                .equals().get("ACTIVE")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void conversionFailuresNameTheColumn() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.price", "cheap")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRICE");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.active", "maybe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.released_on", "15/01/2026")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RELEASED_ON");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.created_at", "not-a-time")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATED_AT");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.id", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }

    @Test
    void unsupportedCategoryIsRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.raw", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RAW");
    }

    @Test
    void containsIsOnlyAllowedOnTextColumns() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("contains.price", "5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRICE");
    }

    @Test
    void unknownColumnsAreRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("fields", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq.missing", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("contains.missing", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("sort", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void unknownParametersAreRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("bogus", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("eq", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eq");
        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("contains", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains");
    }

    @Test
    void moreThanTenFiltersAreRejected() {
        EntityModel tags = textEntity("TAGS", 12);
        Map<String, List<String>> ten = new LinkedHashMap<>();
        for (int i = 1; i <= 10; i++) {
            ten.put("contains.t" + i, List.of("x"));
        }

        assertThat(parser().parse(tags, ten).contains()).hasSize(10);

        ten.put("contains.t11", List.of("x"));
        assertThatThrownBy(() -> parser().parse(tags, ten))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filters");
    }

    @Test
    void moreThanThreeSortColumnsAreRejected() {
        assertThat(parser().parse(PRODUCTS, params("sort", "name,-id,price")).sorts()).hasSize(3);

        assertThatThrownBy(() -> parser().parse(PRODUCTS, params("sort", "name,-id,price,active")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort");
    }

    @Test
    void duplicateFilterColumnIsRejected() {
        assertThatThrownBy(() -> parser().parse(PRODUCTS, Map.of(
                "eq.price", List.of("1"),
                "eq.PRICE", List.of("2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRICE");
    }

    @Test
    void combinedQueryParsesEveryForm() {
        QuerySpec spec = parser().parse(PRODUCTS, Map.of(
                "fields", List.of("name,price"),
                "sort", List.of("-price,name"),
                "limit", List.of("5"),
                "offset", List.of("10"),
                "eq.active", List.of("true"),
                "contains.name", List.of("phone")));

        assertThat(spec.fields()).containsExactly("NAME", "PRICE");
        assertThat(spec.sorts()).containsExactly(
                new QuerySpec.Sort("PRICE", QuerySpec.Direction.DESC),
                new QuerySpec.Sort("NAME", QuerySpec.Direction.ASC));
        assertThat(spec.limit()).isEqualTo(5);
        assertThat(spec.offset()).isEqualTo(10);
        assertThat(spec.equals()).containsEntry("ACTIVE", Boolean.TRUE);
        assertThat(spec.contains()).containsEntry("NAME", "phone");
    }

    @Test
    void pageSizesComeFromProperties() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setDefaultPageSize(12);
        properties.setMaxPageSize(34);

        QuerySpec spec = new QueryParameterParser(properties).parse(PRODUCTS, Map.of());

        assertThat(spec.limit()).isEqualTo(12);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static QueryParameterParser parser() {
        return new QueryParameterParser(50, 200);
    }

    private static Map<String, List<String>> params(String key, String value) {
        return Map.of(key, List.of(value));
    }

    private static EntityModel textEntity(String name, int columnCount) {
        List<ColumnModel> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnModel("T" + i, "varchar(20)", ColumnCategory.TEXT, true));
        }
        return new EntityModel(name, "table", columns, List.of());
    }
}
