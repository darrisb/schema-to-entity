package com.example.dynamicmeta.data;

import com.example.dynamicmeta.connection.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DA-004: safe SELECT and COUNT SQL per database. The builder has no database
 * access; these tests assert exact SQL and bound parameters.
 */
class SelectBuilderTests {

    private static final EntityModel CUSTOMERS = new EntityModel(
            "CUSTOMERS",
            "table",
            List.of(
                    new ColumnModel("ID", "bigint", ColumnCategory.NUMBER, false),
                    new ColumnModel("NAME", "varchar(80)", ColumnCategory.TEXT, false),
                    new ColumnModel("EMAIL", "varchar(200)", ColumnCategory.TEXT, true)),
            List.of("ID"));

    @Test
    void allColumnsForPostgres() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(List.of(), Map.of(), Map.of(), null, null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\"");
        assertThat(statement.parameters()).isEmpty();
    }

    @Test
    void selectedFieldsKeepRequestOrder() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(List.of("EMAIL", "ID"), Map.of(), Map.of(), null, null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"EMAIL\", \"ID\" FROM \"public\".\"CUSTOMERS\"");
        assertThat(statement.parameters()).isEmpty();
    }

    @Test
    void requestedColumnsUseDiscoveredSpelling() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(List.of("id", "Name"), Map.of(), Map.of(), null, null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\" FROM \"public\".\"CUSTOMERS\"");
    }

    @Test
    void equalsAndContainsFiltersUseBoundParameters() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of("ID", 7), Map.of("NAME", "acme"), null, null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\""
                        + " WHERE \"ID\" = ? AND LOWER(\"NAME\") LIKE LOWER(?) ESCAPE '!'");
        assertThat(statement.parameters()).containsExactly(7, "%acme%");
    }

    @Test
    void sortDescending() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of(),
                        new QuerySpec.Sort("NAME", QuerySpec.Direction.DESC), null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\""
                        + " ORDER BY \"NAME\" DESC");
    }

    @Test
    void multipleSortColumnsKeepPriorityOrder() {
        QuerySpec query = new QuerySpec(List.of(), Map.of(), Map.of(),
                List.of(new QuerySpec.Sort("NAME", QuerySpec.Direction.ASC),
                        new QuerySpec.Sort("ID", QuerySpec.Direction.DESC)),
                null, null);

        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, query);

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\""
                        + " ORDER BY \"NAME\" ASC, \"ID\" DESC");
    }

    @Test
    void postgresPagingUsesLimitOffset() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of(),
                        new QuerySpec.Sort("ID", QuerySpec.Direction.ASC), 10, 20));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\""
                        + " ORDER BY \"ID\" ASC LIMIT ? OFFSET ?");
        assertThat(statement.parameters()).containsExactly(10, 20);
    }

    @Test
    void h2AndMysqlAndMariadbPagingUseLimitOffset() {
        QuerySpec query = spec(List.of(), Map.of(), Map.of(), null, 5, 15);

        assertThat(builder(DatabaseType.H2, "STE_TEST", "PUBLIC").select(CUSTOMERS, query).sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"PUBLIC\".\"CUSTOMERS\""
                        + " LIMIT ? OFFSET ?");
        assertThat(builder(DatabaseType.MYSQL, "shop", null).select(CUSTOMERS, query).sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " LIMIT ? OFFSET ?");
        assertThat(builder(DatabaseType.MARIADB, null, "shop").select(CUSTOMERS, query).sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " LIMIT ? OFFSET ?");
    }

    @Test
    void mysqlOffsetWithoutLimitUsesMaxBigintLimit() {
        QuerySpec query = spec(List.of(), Map.of(), Map.of(), null, null, 20);

        SqlStatement mysql = builder(DatabaseType.MYSQL, "shop", null).select(CUSTOMERS, query);
        assertThat(mysql.sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " LIMIT 18446744073709551615 OFFSET ?");
        assertThat(mysql.parameters()).containsExactly(20);

        SqlStatement mariaDb = builder(DatabaseType.MARIADB, null, "shop").select(CUSTOMERS, query);
        assertThat(mariaDb.sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " LIMIT 18446744073709551615 OFFSET ?");
        assertThat(mariaDb.parameters()).containsExactly(20);

        SqlStatement postgres = builder(DatabaseType.POSTGRESQL, null, "public").select(CUSTOMERS, query);
        assertThat(postgres.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\""
                        + " OFFSET ?");
        assertThat(postgres.parameters()).containsExactly(20);
    }

    @Test
    void qualifiesTablePerDatabase() {
        QuerySpec all = spec(List.of(), Map.of(), Map.of(), null, null, null);

        assertThat(builder(DatabaseType.POSTGRESQL, "shopdb", "public").select(CUSTOMERS, all).sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\"");
        assertThat(builder(DatabaseType.ORACLE, null, "APP").select(CUSTOMERS, all).sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"APP\".\"CUSTOMERS\"");
        assertThat(builder(DatabaseType.H2, "STE_TEST", "PUBLIC").select(CUSTOMERS, all).sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"PUBLIC\".\"CUSTOMERS\"");
        assertThat(builder(DatabaseType.MYSQL, "shop", null).select(CUSTOMERS, all).sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`");
        assertThat(builder(DatabaseType.MARIADB, null, "shop").select(CUSTOMERS, all).sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`");
        assertThat(builder(DatabaseType.SQLSERVER, "shop", "dbo").select(CUSTOMERS, all).sql())
                .isEqualTo("SELECT [ID], [NAME], [EMAIL] FROM [shop].[dbo].[CUSTOMERS]");
    }

    @Test
    void sqlServerPagingWithoutSortUsesSelectNull() {
        SqlStatement statement = builder(DatabaseType.SQLSERVER, "shop", "dbo")
                .select(CUSTOMERS, spec(List.of(), Map.of(), Map.of(), null, 10, 20));

        assertThat(statement.sql())
                .isEqualTo("SELECT [ID], [NAME], [EMAIL] FROM [shop].[dbo].[CUSTOMERS]"
                        + " ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(statement.parameters()).containsExactly(20, 10);
    }

    @Test
    void sqlServerSortWithPaging() {
        SqlStatement statement = builder(DatabaseType.SQLSERVER, "shop", "dbo")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of(),
                        new QuerySpec.Sort("NAME", QuerySpec.Direction.DESC), 10, 20));

        assertThat(statement.sql())
                .isEqualTo("SELECT [ID], [NAME], [EMAIL] FROM [shop].[dbo].[CUSTOMERS]"
                        + " ORDER BY [NAME] DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(statement.parameters()).containsExactly(20, 10);
    }

    @Test
    void oraclePagingUsesOffsetFetch() {
        SqlStatement statement = builder(DatabaseType.ORACLE, null, "APP")
                .select(CUSTOMERS, spec(List.of(), Map.of(), Map.of(), null, 10, 20));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"APP\".\"CUSTOMERS\""
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(statement.parameters()).containsExactly(20, 10);
    }

    @Test
    void quoteCharactersInsideIdentifiersAreDoubled() {
        EntityModel postgres = new EntityModel("WE\"IRD", "table",
                List.of(new ColumnModel("NA\"ME", "varchar(10)", ColumnCategory.TEXT, true)),
                List.of());
        QuerySpec all = spec(List.of(), Map.of(), Map.of(), null, null, null);

        assertThat(builder(DatabaseType.POSTGRESQL, null, "pu\"b").select(postgres, all).sql())
                .isEqualTo("SELECT \"NA\"\"ME\" FROM \"pu\"\"b\".\"WE\"\"IRD\"");

        EntityModel mysql = new EntityModel("TA`BLE", "table",
                List.of(new ColumnModel("NA`ME", "varchar(10)", ColumnCategory.TEXT, true)),
                List.of());
        assertThat(builder(DatabaseType.MYSQL, "db`1", null).select(mysql, all).sql())
                .isEqualTo("SELECT `NA``ME` FROM `db``1`.`TA``BLE`");

        EntityModel sqlServer = new EntityModel("TA]BLE", "table",
                List.of(new ColumnModel("NA]ME", "varchar(10)", ColumnCategory.TEXT, true)),
                List.of());
        assertThat(builder(DatabaseType.SQLSERVER, "db]1", "dbo").select(sqlServer, all).sql())
                .isEqualTo("SELECT [NA]]ME] FROM [db]]1].[dbo].[TA]]BLE]");
    }

    @Test
    void containsEscapesWildcardsAndEscapeCharacter() {
        SqlStatement statement = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of("NAME", "50%_off"), null, null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT \"ID\", \"NAME\", \"EMAIL\" FROM \"public\".\"CUSTOMERS\""
                        + " WHERE LOWER(\"NAME\") LIKE LOWER(?) ESCAPE '!'");
        assertThat(statement.parameters()).containsExactly("%50!%!_off%");

        SqlStatement withBackslash = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of("NAME", "a\\b%c_d"), null, null, null));
        // Backslash is ordinary text; only the '!' escape character is doubled.
        assertThat(withBackslash.parameters()).containsExactly("%a\\b!%c!_d%");

        SqlStatement withBang = builder(DatabaseType.POSTGRESQL, null, "public")
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of("NAME", "wow!50%"), null, null, null));
        assertThat(withBang.parameters()).containsExactly("%wow!!50!%%");
    }

    @Test
    void mysqlAndMariaDbContainsUseTheSameEscapeClause() {
        QuerySpec query = spec(
                List.of(), Map.of(), Map.of("NAME", "acme"), null, null, null);

        SqlStatement mysql = builder(DatabaseType.MYSQL, "shop", null).select(CUSTOMERS, query);
        assertThat(mysql.sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " WHERE LOWER(`NAME`) LIKE LOWER(?) ESCAPE '!'");
        assertThat(mysql.parameters()).containsExactly("%acme%");

        SqlStatement mariaDb = builder(DatabaseType.MARIADB, null, "shop").select(CUSTOMERS, query);
        assertThat(mariaDb.sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " WHERE LOWER(`NAME`) LIKE LOWER(?) ESCAPE '!'");
        assertThat(mariaDb.parameters()).containsExactly("%acme%");
    }

    @Test
    void mysqlContainsEscapesWildcardsAndKeepsBackslashLiteral() {
        SqlStatement statement = builder(DatabaseType.MYSQL, "shop", null)
                .select(CUSTOMERS, spec(
                        List.of(), Map.of(), Map.of("NAME", "a\\b%c_d"), null, null, null));

        assertThat(statement.sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`"
                        + " WHERE LOWER(`NAME`) LIKE LOWER(?) ESCAPE '!'");
        assertThat(statement.parameters()).containsExactly("%a\\b!%c!_d%");
    }

    @Test
    void mariaDbQualifiesTablesWithTheCatalog() {
        // MariaDB, like MySQL, reports each database as a catalog with no schema.
        SqlStatement statement = builder(DatabaseType.MARIADB, "shop", null)
                .select(CUSTOMERS, spec(List.of(), Map.of(), Map.of(), null, null, null));
        assertThat(statement.sql())
                .isEqualTo("SELECT `ID`, `NAME`, `EMAIL` FROM `shop`.`CUSTOMERS`");
    }

    @Test
    void countUsesSameWhereAndParametersWithoutSortOrPaging() {
        QuerySpec query = spec(
                List.of("ID"), Map.of("ID", 7), Map.of("NAME", "acme"),
                new QuerySpec.Sort("NAME", QuerySpec.Direction.DESC), 10, 20);

        SqlStatement count = builder(DatabaseType.POSTGRESQL, null, "public")
                .buildCount(CUSTOMERS, query);

        assertThat(count.sql())
                .isEqualTo("SELECT COUNT(*) FROM \"public\".\"CUSTOMERS\""
                        + " WHERE \"ID\" = ? AND LOWER(\"NAME\") LIKE LOWER(?) ESCAPE '!'");
        assertThat(count.parameters()).containsExactly(7, "%acme%");
    }

    @Test
    void countUsesDatabaseQuoting() {
        SqlStatement count = builder(DatabaseType.SQLSERVER, "shop", "dbo")
                .buildCount(CUSTOMERS, spec(List.of(), Map.of(), Map.of(), null, null, null));

        assertThat(count.sql())
                .isEqualTo("SELECT COUNT(*) FROM [shop].[dbo].[CUSTOMERS]");
        assertThat(count.parameters()).isEmpty();
    }

    @Test
    void unknownColumnsAreRejected() {
        SelectBuilder builder = builder(DatabaseType.POSTGRESQL, null, "public");

        assertThatThrownBy(() -> builder.select(CUSTOMERS,
                spec(List.of("MISSING"), Map.of(), Map.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
        assertThatThrownBy(() -> builder.select(CUSTOMERS,
                spec(List.of(), Map.of("MISSING", 1), Map.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
        assertThatThrownBy(() -> builder.select(CUSTOMERS,
                spec(List.of(), Map.of(), Map.of("MISSING", "x"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
        assertThatThrownBy(() -> builder.select(CUSTOMERS,
                spec(List.of(), Map.of(), Map.of(),
                        new QuerySpec.Sort("MISSING", QuerySpec.Direction.ASC), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
        assertThatThrownBy(() -> builder.buildCount(CUSTOMERS,
                spec(List.of(), Map.of("MISSING", 1), Map.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static SelectBuilder builder(DatabaseType type, String catalog, String schema) {
        return new SelectBuilder(type, catalog, schema);
    }

    private static QuerySpec spec(List<String> fields, Map<String, Object> equals,
                                  Map<String, String> contains, QuerySpec.Sort sort,
                                  Integer limit, Integer offset) {
        return new QuerySpec(fields, equals, contains, sort, limit, offset);
    }
}
