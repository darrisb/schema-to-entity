package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDiscoveryServiceTests {

    private static final ConnectionPolicy DEFAULT_POLICY = new ConnectionPolicy(List.of(), false);

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

    private static void createWildcardSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table a_b (id bigint primary key, alpha varchar(20))");
            statement.execute("create table axb (id bigint primary key, beta varchar(20))");
        }
    }
}
