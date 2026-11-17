package com.example.dynamicmeta.connection;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamespaceResolverTests {

    private final NamespaceResolver resolver = new NamespaceResolver();

    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;

    // -------------------------------------------------------------------
    // Per-database resolution
    // -------------------------------------------------------------------

    @Test
    void postgresUsesCatalogAndSchemaWithPublicDefault() throws Exception {
        when(connection.getCatalog()).thenReturn("mydb");
        when(connection.getSchema()).thenReturn("app_schema");

        // Requested schema overrides connection schema
        NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.POSTGRESQL, connection, "custom");
        assertThat(ns.catalog()).isEqualTo("mydb");
        assertThat(ns.schemaPattern()).isEqualTo("custom");

        // No requested schema: falls back to connection schema
        ns = resolver.resolve(DatabaseType.POSTGRESQL, connection, null);
        assertThat(ns.catalog()).isEqualTo("mydb");
        assertThat(ns.schemaPattern()).isEqualTo("app_schema");
    }

    @Test
    void postgresDefaultsToPublicWhenNoSchema() throws Exception {
        when(connection.getCatalog()).thenReturn("mydb");
        when(connection.getSchema()).thenReturn(null);

        NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.POSTGRESQL, connection, null);
        assertThat(ns.catalog()).isEqualTo("mydb");
        assertThat(ns.schemaPattern()).isEqualTo("public");
    }

    @Test
    void mysqlUsesRequestedSchemaAsCatalog() throws Exception {
        when(connection.getCatalog()).thenReturn("default_db");

        // Requested schema becomes the catalog
        NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.MYSQL, connection, "my_app");
        assertThat(ns.catalog()).isEqualTo("my_app");
        assertThat(ns.schemaPattern()).isNull();

        // No requested schema: falls back to connection catalog
        ns = resolver.resolve(DatabaseType.MYSQL, connection, null);
        assertThat(ns.catalog()).isEqualTo("default_db");
        assertThat(ns.schemaPattern()).isNull();
    }

    @Test
    void mariadbSameAsMysql() throws Exception {
        when(connection.getCatalog()).thenReturn("mariadb_default");

        NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.MARIADB, connection, "mariadb_app");
        assertThat(ns.catalog()).isEqualTo("mariadb_app");
        assertThat(ns.schemaPattern()).isNull();
    }

    @Test
    void oracleUsesUppercaseSchemaAndNullCatalog() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getUserName()).thenReturn("hr_user");

        // Unquoted requested schema: uppercased
        NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.ORACLE, connection, "hr");
        assertThat(ns.catalog()).isNull();
        assertThat(ns.schemaPattern()).isEqualTo("HR");

        // Quoted requested schema: case preserved
        ns = resolver.resolve(DatabaseType.ORACLE, connection, "\"MySchema\"");
        assertThat(ns.catalog()).isNull();
        assertThat(ns.schemaPattern()).isEqualTo("MySchema");

        // No requested schema: falls back to connection user, uppercased
        ns = resolver.resolve(DatabaseType.ORACLE, connection, null);
        assertThat(ns.catalog()).isNull();
        assertThat(ns.schemaPattern()).isEqualTo("HR_USER");
    }

    @Test
    void sqlServerUsesCatalogAndDboDefault() throws Exception {
        when(connection.getCatalog()).thenReturn("master");

        // Requested schema overrides default
        NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.SQLSERVER, connection, "sales");
        assertThat(ns.catalog()).isEqualTo("master");
        assertThat(ns.schemaPattern()).isEqualTo("sales");

        // No requested schema: defaults to dbo
        ns = resolver.resolve(DatabaseType.SQLSERVER, connection, null);
        assertThat(ns.catalog()).isEqualTo("master");
        assertThat(ns.schemaPattern()).isEqualTo("dbo");
    }

    @Test
    void h2UsesConnectionCatalogAndSchema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_resolver;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection conn = ds.getConnection()) {
            NamespaceResolver.ResolvedNamespace ns = resolver.resolve(DatabaseType.H2, conn, "MY_SCHEMA");
            assertThat(ns.schemaPattern()).isEqualTo("MY_SCHEMA");
        }
    }

    @Test
    void nullDatabaseTypeFallsBackToConnectionDefaults() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_fallback;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection conn = ds.getConnection()) {
            NamespaceResolver.ResolvedNamespace ns = resolver.resolve(null, conn, null);
            // H2 returns "PUBLIC" as default schema
            assertThat(ns.schemaPattern()).isIn("PUBLIC", "public");
        }
    }

    // -------------------------------------------------------------------
    // fromProductName
    // -------------------------------------------------------------------

    @Test
    void fromProductNameMatchesPostgres() {
        assertThat(NamespaceResolver.fromProductName("PostgreSQL")).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(NamespaceResolver.fromProductName("postgresql 16")).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void fromProductNameMatchesMysql() {
        assertThat(NamespaceResolver.fromProductName("MySQL")).isEqualTo(DatabaseType.MYSQL);
        assertThat(NamespaceResolver.fromProductName("mysql 8.0")).isEqualTo(DatabaseType.MYSQL);
    }

    @Test
    void fromProductNameMatchesMariaDb() {
        assertThat(NamespaceResolver.fromProductName("MariaDB")).isEqualTo(DatabaseType.MARIADB);
    }

    @Test
    void fromProductNameMatchesSqlServer() {
        assertThat(NamespaceResolver.fromProductName("Microsoft SQL Server")).isEqualTo(DatabaseType.SQLSERVER);
        assertThat(NamespaceResolver.fromProductName("SQL Server")).isEqualTo(DatabaseType.SQLSERVER);
    }

    @Test
    void fromProductNameMatchesOracle() {
        assertThat(NamespaceResolver.fromProductName("Oracle")).isEqualTo(DatabaseType.ORACLE);
        assertThat(NamespaceResolver.fromProductName("Oracle Database 19c")).isEqualTo(DatabaseType.ORACLE);
    }

    @Test
    void fromProductNameMatchesH2() {
        assertThat(NamespaceResolver.fromProductName("H2")).isEqualTo(DatabaseType.H2);
    }

    @Test
    void fromProductNameReturnsNullForUnknown() {
        assertThat(NamespaceResolver.fromProductName("SomeUnknownDB")).isNull();
    }

    // -------------------------------------------------------------------
    // Schema name validation
    // -------------------------------------------------------------------

    @Test
    void acceptsNullAndBlankSchemaNames() {
        // Should not throw
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, null);
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "");
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "   ");
    }

    @Test
    void acceptsValidSchemaNames() {
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "public");
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "app_schema_v2");
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "$schema");
        NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "Schema123");
    }

    @Test
    void rejectsSchemaNameWithSpaces() {
        assertThatThrownBy(() ->
                NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, "my schema"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
    }

    @Test
    void rejectsSchemaNameWithSpecialCharacters() {
        assertThatThrownBy(() ->
                NamespaceResolver.validateSchemaName(DatabaseType.MYSQL, "my-schema"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
    }

    @Test
    void rejectsOversizeSchemaName() {
        String longName = "a".repeat(129);
        assertThatThrownBy(() ->
                NamespaceResolver.validateSchemaName(DatabaseType.POSTGRESQL, longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    @Test
    void acceptsQuotedOracleSchemaNamesWithSpaces() {
        // Quoted Oracle names are allowed to contain spaces
        NamespaceResolver.validateSchemaName(DatabaseType.ORACLE, "\"My Schema\"");
    }

    // -------------------------------------------------------------------
    // Table filter size validation
    // -------------------------------------------------------------------

    @Test
    void acceptsTableFilterWithinLimit() {
        List<String> tables = Collections.nCopies(500, "table");
        NamespaceResolver.validateTableFilterSize(tables); // should not throw
    }

    @Test
    void acceptsNullAndSmallTableFilters() {
        NamespaceResolver.validateTableFilterSize(null);
        NamespaceResolver.validateTableFilterSize(Collections.emptyList());
        NamespaceResolver.validateTableFilterSize(List.of("one", "two"));
    }

    @Test
    void rejectsOversizeTableFilter() {
        List<String> tables = Collections.nCopies(501, "table");
        assertThatThrownBy(() ->
                NamespaceResolver.validateTableFilterSize(tables))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }
}
