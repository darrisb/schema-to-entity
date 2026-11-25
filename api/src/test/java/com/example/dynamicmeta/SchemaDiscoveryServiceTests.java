package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.connection.ViewDependencyReader;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDiscoveryServiceTests {

    private static final ConnectionPolicy DEFAULT_POLICY = new ConnectionPolicy(List.of(), false);

    @Mock private Connection connection;
    @Mock private DatabaseMetaData metadata;
    @Mock private ResultSet tableTypes;
    @Mock private ResultSet tables;
    @Mock private ResultSet columns;
    @Mock private PreparedStatement statement;
    @Mock private ResultSet dependencies;
    @Mock private ResultSet primaryKeys;
    @Mock private ResultSet importedKeys;

    @Test
    void discoversTheContainingApplicationsDataSource() throws Exception {
        JdbcDataSource dataSource = dataSource("discovery_host");
        createSchema(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.discover("PUBLIC", List.of());

        assertThat(result).containsEntry("dialect", "h2").containsEntry("schema", "PUBLIC");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).extracting(table -> table.get("name")).contains("CUSTOMERS", "ORDERS");
        Map<String, Object> orders = tables.stream()
                .filter(table -> "ORDERS".equals(table.get("name")))
                .findFirst().orElseThrow();
        assertThat((List<?>) orders.get("foreignKeys")).hasSize(1);
        assertThat(orders.get("primaryKey")).isEqualTo("ID");
    }

    @Test
    void discoversViewsAlongsideTablesAndTagsEveryObjectWithItsKind() throws Exception {
        JdbcDataSource dataSource = dataSource("views");
        createSchemaWithView(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.discover("PUBLIC", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).extracting(table -> table.get("name"))
                .containsExactlyInAnyOrder("CUSTOMERS", "CUSTOMER_NAMES");

        Map<String, Object> customers = tableNamed(tables, "CUSTOMERS");
        assertThat(customers).containsEntry("kind", "table");
        assertThat(customers.get("primaryKey")).isEqualTo("ID");
        assertThat((List<?>) customers.get("foreignKeys")).isEmpty();

        Map<String, Object> view = tableNamed(tables, "CUSTOMER_NAMES");
        assertThat(view).containsEntry("kind", "view");
        assertThat(view).doesNotContainKey("primaryKey");
        assertThat((List<?>) view.get("foreignKeys")).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> viewColumns = (List<Map<String, Object>>) view.get("columns");
        assertThat(viewColumns).extracting(column -> column.get("name")).containsExactly("ID", "NAME");
    }

    @Test
    void excludesViewsWhenIncludeViewsIsFalse() throws Exception {
        assertThat(new SchemaToEntityProperties().isIncludeViews()).isTrue();

        JdbcDataSource dataSource = dataSource("views_excluded");
        createSchemaWithView(dataSource);
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setIncludeViews(false);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, properties, DEFAULT_POLICY, factory());

        Map<String, Object> result = service.discover("PUBLIC", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).extracting(table -> table.get("name")).containsExactly("CUSTOMERS");
        assertThat(tables).allMatch(table -> "table".equals(table.get("kind")));
    }

    @Test
    void appliesTheTableFilterToViews() throws Exception {
        JdbcDataSource dataSource = dataSource("views_filter");
        createSchemaWithView(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.discover("PUBLIC", List.of("customer_names"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).extracting(table -> table.get("name")).containsExactly("CUSTOMER_NAMES");
        assertThat(tables.getFirst()).containsEntry("kind", "view");
    }

    @Test
    void recordsBaseTablesForViewsButNotForTables() throws Exception {
        JdbcDataSource dataSource = dataSource("view_dependencies");
        createSchemaWithView(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.discover("PUBLIC", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> customers = tableNamed(tables, "CUSTOMERS");
        assertThat(customers).doesNotContainKey("baseTables");

        Map<String, Object> view = tableNamed(tables, "CUSTOMER_NAMES");
        assertThat(view).containsEntry("kind", "view");
        // H2 has no information_schema.view_table_usage, so discovery succeeds with no base tables.
        assertThat(view.get("baseTables")).isEqualTo(List.of());
    }

    @Test
    void discoveryStillSucceedsWhenTheViewDependencyQueryFails() throws Exception {
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getCatalog()).thenReturn("mydb");
        when(connection.getSchema()).thenReturn("public");
        when(metadata.getTableTypes()).thenReturn(tableTypes);
        when(tableTypes.next()).thenReturn(true, false);
        when(tableTypes.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("customer_names");
        when(tables.getString("TABLE_TYPE")).thenReturn("VIEW");
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(columns.next()).thenReturn(false);
        when(connection.prepareStatement(anyString()))
                .thenThrow(new SQLException("permission denied"));
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                null, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.inspect(connection, "public", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discovered = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> view = tableNamed(discovered, "customer_names");
        assertThat(view).containsEntry("kind", "view");
        assertThat(view.get("baseTables")).isEqualTo(List.of());
    }

    @Test
    void attachesCatalogBaseTablesToTheViewAndLeavesTablesWithoutTheKey() throws Exception {
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getCatalog()).thenReturn("mydb");
        when(metadata.getTableTypes()).thenReturn(tableTypes);
        when(tableTypes.next()).thenReturn(true, false);
        when(tableTypes.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true, true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("customer_names", "customers");
        when(tables.getString("TABLE_TYPE")).thenReturn("VIEW", "TABLE");
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(columns.next()).thenReturn(false);
        when(metadata.getPrimaryKeys(any(), any(), any())).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(false);
        when(metadata.getImportedKeys(any(), any(), any())).thenReturn(importedKeys);
        when(importedKeys.next()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(dependencies);
        when(dependencies.next()).thenReturn(true, false);
        when(dependencies.getString("view_name")).thenReturn("customer_names");
        when(dependencies.getString("table_name")).thenReturn("customers");

        SchemaDiscoveryService service = new SchemaDiscoveryService(
                null, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.inspect(connection, "public", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discovered = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> view = tableNamed(discovered, "customer_names");
        assertThat(view).containsEntry("kind", "view");
        assertThat(view.get("baseTables")).isEqualTo(List.of("customers"));

        Map<String, Object> customers = tableNamed(discovered, "customers");
        assertThat(customers).doesNotContainKey("baseTables");
    }

    @Test
    void attachesCatalogBaseTablesToMaterializedViews() throws Exception {
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getCatalog()).thenReturn("mydb");
        when(metadata.getTableTypes()).thenReturn(tableTypes);
        when(tableTypes.next()).thenReturn(true, false);
        when(tableTypes.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("customer_names");
        when(tables.getString("TABLE_TYPE")).thenReturn("MATERIALIZED VIEW");
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(columns.next()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(dependencies);
        when(dependencies.next()).thenReturn(true, false);
        when(dependencies.getString("view_name")).thenReturn("customer_names");
        when(dependencies.getString("table_name")).thenReturn("customers");

        SchemaDiscoveryService service = new SchemaDiscoveryService(
                null, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.inspect(connection, "public", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discovered = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> view = tableNamed(discovered, "customer_names");
        assertThat(view).containsEntry("kind", "materialized-view");
        assertThat(view.get("baseTables")).isEqualTo(List.of("customers"));
    }

    @Test
    void infersViewForeignKeysFromBaseTableColumns() throws Exception {
        JdbcDataSource dataSource = dataSource("view_fk_inference");
        createSchemaWithOrderView(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        try (MockedConstruction<ViewDependencyReader> mocked = mockConstruction(ViewDependencyReader.class,
                (reader, context) -> when(reader.read(any(), any(), any(), any()))
                        .thenReturn(Map.of("order_overview", List.of("orders"))))) {
            Map<String, Object> result = service.discover("PUBLIC", List.of());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
            Map<String, Object> view = tableNamed(tables, "ORDER_OVERVIEW");
            assertThat(view).containsEntry("kind", "view");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> inferred = (List<Map<String, Object>>) view.get("foreignKeys");
            assertThat(inferred).hasSize(1);
            assertThat(inferred.getFirst())
                    .containsEntry("column", "CUSTOMER_ID")
                    .containsEntry("referencedTable", "CUSTOMERS")
                    .containsEntry("referencedColumn", "ID")
                    .containsEntry("origin", "inferred");

            // The declared foreign key on the base table is unchanged.
            Map<String, Object> orders = tableNamed(tables, "ORDERS");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> declared = (List<Map<String, Object>>) orders.get("foreignKeys");
            assertThat(declared).hasSize(1);
            assertThat(declared.getFirst())
                    .containsEntry("column", "CUSTOMER_ID")
                    .doesNotContainKey("origin");
        }
    }

    @Test
    void skipsAmbiguousViewForeignKeyMatches() throws Exception {
        JdbcDataSource dataSource = dataSource("view_fk_ambiguous");
        createSchemaWithAmbiguousView(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        try (MockedConstruction<ViewDependencyReader> mocked = mockConstruction(ViewDependencyReader.class,
                (reader, context) -> when(reader.read(any(), any(), any(), any()))
                        .thenReturn(Map.of("customer_feed", List.of("orders", "purchases"))))) {
            Map<String, Object> result = service.discover("PUBLIC", List.of());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
            Map<String, Object> view = tableNamed(tables, "CUSTOMER_FEED");
            assertThat(view).containsEntry("kind", "view");
            assertThat((List<?>) view.get("foreignKeys")).isEmpty();
        }
    }

    @Test
    void supportsAnExplicitJdbcUrlWithoutReplacingTheHostDataSource() throws Exception {
        JdbcDataSource host = dataSource("discovery_host2");
        JdbcDataSource external = dataSource("discovery_external");
        createSchema(external);
        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setExplicitConnectionsEnabled(true);
        props.setAllowEmbeddedDatabases(true);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                host, props, new ConnectionPolicy(List.of(), true), factory());

        Map<String, Object> result = service.discover(new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:discovery_external;DB_CLOSE_DELAY=-1", "sa", "", "PUBLIC",
                List.of("CUSTOMERS"), null, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).extracting(table -> table.get("name")).containsExactly("CUSTOMERS");
    }

    @Test
    void doesNotMixColumnsAcrossTablesWhoseNamesDifferByWildcardCharacters() throws Exception {
        JdbcDataSource dataSource = dataSource("wildcard_escape");
        createWildcardSchema(dataSource);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                dataSource, new SchemaToEntityProperties(), DEFAULT_POLICY, factory());

        Map<String, Object> result = service.discover("PUBLIC", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        Map<String, Object> underscore = tables.stream()
                .filter(table -> "A_B".equals(table.get("name")))
                .findFirst().orElseThrow();
        Map<String, Object> plain = tables.stream()
                .filter(table -> "AXB".equals(table.get("name")))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> underscoreColumns = (List<Map<String, Object>>) underscore.get("columns");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plainColumns = (List<Map<String, Object>>) plain.get("columns");

        assertThat(underscoreColumns).extracting(column -> column.get("name")).containsExactly("ID", "ALPHA");
        assertThat(plainColumns).extracting(column -> column.get("name")).containsExactly("ID", "BETA");
    }

    @Test
    void autoConfiguresWhenADataSourceIsPresentAndCanBeDisabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource("autoconfig"));

        runner.run(context -> assertThat(context).hasSingleBean(SchemaDiscoveryService.class));
        runner.withPropertyValues("schema-to-entity.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SchemaDiscoveryService.class));
    }

    @Test
    void contributesTheControllerToServletApplications() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource("web"))
                .run(context -> {
                    assertThat(context).hasSingleBean(SchemaDiscoveryService.class);
                    assertThat(context).hasSingleBean(SchemaDiscoveryController.class);
                });
    }

    private static JdbcConnectionFactory factory() {
        return new JdbcConnectionFactory(Duration.ZERO, Duration.ZERO);
    }

    private static Map<String, Object> tableNamed(List<Map<String, Object>> tables, String name) {
        return tables.stream()
                .filter(table -> name.equals(table.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No table named " + name));
    }

    private static JdbcDataSource dataSource(String database) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint generated by default as identity primary key, name varchar(80) not null)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null, constraint fk_customer foreign key (customer_id) references customers(id))");
        }
    }

    private static void createSchemaWithView(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint generated by default as identity primary key, name varchar(80) not null)");
            statement.execute("create view customer_names as select id, name from customers");
        }
    }

    private static void createSchemaWithOrderView(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint generated by default as identity primary key, name varchar(80) not null)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null, " +
                    "constraint fk_customer foreign key (customer_id) references customers(id))");
            // The quoted alias keeps the view column lower case, proving the match ignores case.
            statement.execute("create view order_overview as select id, customer_id as \"customer_id\" from orders");
        }
    }

    private static void createSchemaWithAmbiguousView(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint generated by default as identity primary key, name varchar(80) not null)");
            statement.execute("create table suppliers (id bigint generated by default as identity primary key, name varchar(80) not null)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null, " +
                    "constraint fk_order_customer foreign key (customer_id) references customers(id))");
            statement.execute("create table purchases (id bigint primary key, customer_id bigint not null, " +
                    "constraint fk_purchase_supplier foreign key (customer_id) references suppliers(id))");
            statement.execute("create view customer_feed as select o.id, o.customer_id from orders o " +
                    "join purchases p on o.id = p.id");
        }
    }

    private static void createWildcardSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table a_b (id bigint primary key, alpha varchar(20))");
            statement.execute("create table axb (id bigint primary key, beta varchar(20))");
        }
    }

    @Test
    void testDisplayTypeWithLength() {
        // Unbounded by size
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("varchar", 0, 0, java.sql.Types.VARCHAR)).isEqualTo("varchar");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("varchar", -1, 0, java.sql.Types.VARCHAR)).isEqualTo("varchar");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("varchar", Integer.MAX_VALUE, 0, java.sql.Types.VARCHAR)).isEqualTo("varchar");

        // Unbounded by type
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("text", 2147483647, 0, java.sql.Types.VARCHAR)).isEqualTo("text");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("clob", 80, 0, java.sql.Types.CLOB)).isEqualTo("clob");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("ntext", 80, 0, java.sql.Types.VARCHAR)).isEqualTo("ntext");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("longtext", 80, 0, java.sql.Types.VARCHAR)).isEqualTo("longtext");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("mediumtext", 80, 0, java.sql.Types.VARCHAR)).isEqualTo("mediumtext");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("tinytext", 80, 0, java.sql.Types.VARCHAR)).isEqualTo("tinytext");

        // Bounded
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("varchar", 80, 0, java.sql.Types.VARCHAR)).isEqualTo("varchar(80)");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("char", 2, 0, java.sql.Types.CHAR)).isEqualTo("char(2)");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("nvarchar", 255, 0, java.sql.Types.NVARCHAR)).isEqualTo("nvarchar(255)");

        // Decimal
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("numeric", 10, 2, java.sql.Types.NUMERIC)).isEqualTo("numeric(10,2)");
        assertThat(SchemaDiscoveryService.getDisplayTypeWithLength("decimal", 12, 0, java.sql.Types.DECIMAL)).isEqualTo("decimal(12,0)");
    }
}