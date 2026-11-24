package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DA-006: the read-only data endpoints, backed by a real H2 database through
 * the SourceRegistry. No INSERT, UPDATE, DELETE or DDL is issued by the API.
 */
class DataControllerTests {

    private static final String DATABASE = "ste_data_controller";
    private static final String JDBC_URL =
            "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1";
    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    private SourceRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        seed();
        mockMvc = buildMockMvc();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
    }

    @Test
    void listsEntitiesWithNameKindKeyAndColumns() throws Exception {
        mockMvc.perform(get("/api/data/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')].name")
                        .value(hasItem("CUSTOMERS")))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')].kind")
                        .value(hasItem("table")))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')].keyColumns[0]")
                        .value(hasItem("ID")))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')]"
                                + ".columns[?(@.name == 'ID')].type")
                        .value(hasItem("bigint")))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')]"
                                + ".columns[?(@.name == 'ID')].category")
                        .value(hasItem("NUMBER")))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')]"
                                + ".columns[?(@.name == 'ID')].nullable")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMERS')]"
                                + ".columns[?(@.name == 'NAME')].nullable")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.name == 'CUSTOMER_NAMES')].kind")
                        .value(hasItem("view")));
    }

    @Test
    void pagesThroughRowsWithTotalLimitAndOffset() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers")
                        .param("sort", "id")
                        .param("limit", "2")
                        .param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.items[0].ID").value(2))
                .andExpect(jsonPath("$.items[1].ID").value(3));

        mockMvc.perform(get("/api/data/orders/customers")
                        .param("sort", "id")
                        .param("limit", "2")
                        .param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].ID").value(4))
                .andExpect(jsonPath("$.items[1].ID").value(5));
    }

    @Test
    void requestWithoutParametersUsesTheConfiguredDefaults() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").doesNotExist());
    }

    @Test
    void defaultLimitComesFromTheConfiguredPageSize() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers").param("sort", "id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").doesNotExist());
    }

    @Test
    void eqAndContainsFiltersAreApplied() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers")
                        .param("eq.active", "true")
                        .param("sort", "id")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].ID").value(hasItem(1)))
                .andExpect(jsonPath("$.items[*].ID").value(hasItem(3)))
                .andExpect(jsonPath("$.items[*].ID").value(hasItem(5)));

        mockMvc.perform(get("/api/data/orders/customers")
                        .param("contains.name", "ar")
                        .param("sort", "id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].ID").value(3));

        mockMvc.perform(get("/api/data/orders/customers")
                        .param("eq.id", "4")
                        .param("fields", "id,name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].NAME").value("Dave"));
    }

    @Test
    void eqTimestampFilterWorks() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers")
                        .param("eq.created", "2026-01-15T10:30:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].ID").value(1));
    }

    @Test
    void sortDescendingOrdersRows() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers")
                        .param("sort", "-id")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ID").value(5))
                .andExpect(jsonPath("$.items[4].ID").value(1));
    }

    @Test
    void fieldsSelectsOnlyTheRequestedColumns() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers")
                        .param("fields", "name,id")
                        .param("sort", "id")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ID").value(1))
                .andExpect(jsonPath("$.items[0].NAME").value("Alice"))
                .andExpect(jsonPath("$.items[0].EMAIL").doesNotExist())
                .andExpect(jsonPath("$.items[0].PRICE").doesNotExist());
    }

    @Test
    void totalFalseSkipsTheCountAndReturnsNull() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers")
                        .param("total", "false")
                        .param("sort", "id")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(nullValue()))
                .andExpect(jsonPath("$.limit").value(2));
    }

    @Test
    void rowsConvertNumbersDatesAndTimestamps() throws Exception {
        // A TIMESTAMP without a time zone comes back as the stored local
        // date-time, independent of the JVM's default zone.
        String expectedTimestamp = "2026-01-15T10:30:00";

        mockMvc.perform(get("/api/data/orders/customers")
                        .param("eq.id", "1")
                        .param("fields", "id,born,created,price,active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ID").value(1))
                .andExpect(jsonPath("$.items[0].BORN").value("1990-05-01"))
                .andExpect(jsonPath("$.items[0].CREATED").value(expectedTimestamp))
                .andExpect(jsonPath("$.items[0].PRICE").value(19.99))
                .andExpect(jsonPath("$.items[0].ACTIVE").value(true));
    }

    @Test
    void detailByKeyReturnsTheRow() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ID").value(1))
                .andExpect(jsonPath("$.NAME").value("Alice"))
                .andExpect(jsonPath("$.EMAIL").value("alice@example.com"));
    }

    @Test
    void detailByTextKeyReturnsTheRow() throws Exception {
        mockMvc.perform(get("/api/data/orders/widgets/w-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.CODE").value("w-1"))
                .andExpect(jsonPath("$.LABEL").value("Widget One"));
    }

    @Test
    void detailByOtherCategoryKeyReturnsTheRow() throws Exception {
        mockMvc.perform(get("/api/data/orders/docs/550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.TITLE").value("Doc"));
    }

    @Test
    void binaryColumnsAreReturnedAsNull() throws Exception {
        mockMvc.perform(get("/api/data/orders/binaries/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ID").value(1))
                .andExpect(jsonPath("$.PAYLOAD").value(nullValue()))
                .andExpect(jsonPath("$.NOTE").value("x"));
    }

    @Test
    void detailByMissingKeyReturns404() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        "No row found in entity 'customers' for the requested key"));
    }

    @Test
    void detailForViewWithoutAKeyReturns400() throws Exception {
        mockMvc.perform(get("/api/data/orders/customer_emails/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This entity has no single-column key"));
    }

    @Test
    void detailForCompositeKeyReturns400() throws Exception {
        mockMvc.perform(get("/api/data/orders/composite/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This entity has no single-column key"));
    }

    @Test
    void unknownSourceReturns404() throws Exception {
        mockMvc.perform(get("/api/data/missing/customers"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown data source: missing"));
    }

    @Test
    void unknownEntityReturns404() throws Exception {
        mockMvc.perform(get("/api/data/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown entity: missing"));
    }

    @Test
    void badParametersReturn400() throws Exception {
        mockMvc.perform(get("/api/data/orders/customers").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("limit")));

        mockMvc.perform(get("/api/data/orders/customers").param("bogus", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("bogus")));
    }

    @Test
    void postIsNotMapped() throws Exception {
        mockMvc.perform(post("/api/data/orders/customers"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void exceptionHandlersUseTheDocumentedStatusesAndRedactSecrets() {
        DataController controller = new DataController(null);

        assertThat(controller.unknownSource(new SourceNotFoundException("missing"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.unknownEntity(new EntityNotFoundException("missing"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.missingRow(new RowNotFoundException("customers"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.badRequest(new IllegalArgumentException("bad parameter"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.queryTimeout(new SQLTimeoutException("slow"))
                .getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(controller.poolTimeout(
                        new SQLTransientConnectionException("pool exhausted"))
                .getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.sqlFailure(new SQLException("database failed"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        String error = controller.sqlFailure(new SQLException(
                        "connect to postgres://user:pass123@host/db"))
                .getBody().get("error");
        assertThat(error).doesNotContain("pass123");
    }

    @Test
    void controllerIsRegisteredOnlyWhenDataApiIsEnabled() {
        JdbcDataSource host = new JdbcDataSource();
        host.setURL("jdbc:h2:mem:ste_data_controller_host;DB_CLOSE_DELAY=-1");
        host.setUser("sa");
        host.setPassword("");

        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> host)
                .withPropertyValues(
                        "schema-to-entity.data-api-enabled=true",
                        "schema-to-entity.allow-embedded-databases=true",
                        "schema-to-entity.sources.orders.url="
                                + "jdbc:h2:mem:ste_data_controller_auto;DB_CLOSE_DELAY=-1",
                        "schema-to-entity.sources.orders.username=sa");

        runner.run(context -> {
            assertThat(context).hasSingleBean(DataController.class);
            assertThat(context).hasSingleBean(DataQueryService.class);
        });

        runner.withPropertyValues("schema-to-entity.data-api-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DataController.class));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private MockMvc buildMockMvc() {
        SourceProperties source = new SourceProperties();
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        source.setViewKeys(Map.of("customer_names", "id"));

        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));
        properties.setDefaultPageSize(2);
        properties.setMaxPageSize(5);
        properties.setQueryTimeout(Duration.ofSeconds(15));

        Map<String, DataSourceDefinition> definitions =
                DataSourceDefinitions.from(properties, PARSER, POLICY);
        registry = new SourceRegistry(definitions, FACTORY, PARSER, Duration.ofSeconds(10));
        SchemaDiscoveryService discovery =
                new SchemaDiscoveryService(null, properties, POLICY, FACTORY);
        SchemaCache cache = new SchemaCache(registry, discovery, properties);
        QueryParameterParser parser = new QueryParameterParser(properties);
        DataQueryService service = new DataQueryService(registry, cache, parser, properties);

        return MockMvcBuilders.standaloneSetup(new DataController(service)).build();
    }

    private static void seed() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("create table customers ("
                    + "id bigint primary key, "
                    + "name varchar(80) not null, "
                    + "email varchar(200), "
                    + "active boolean, "
                    + "created timestamp, "
                    + "born date, "
                    + "price numeric(10,2))");
            statement.execute("insert into customers values "
                    + "(1, 'Alice', 'alice@example.com', true, "
                    + "'2026-01-15 10:30:00', '1990-05-01', 19.99)");
            statement.execute("insert into customers values "
                    + "(2, 'Bob', 'bob@example.com', false, "
                    + "'2026-01-16 11:00:00', '1988-02-02', 5.50)");
            statement.execute("insert into customers values "
                    + "(3, 'Carol', 'carol@example.com', true, "
                    + "'2026-01-17 12:15:00', '1992-07-04', 100)");
            statement.execute("insert into customers values "
                    + "(4, 'Dave', 'dave@example.com', false, "
                    + "'2026-01-18 09:45:00', '1979-11-30', 0.01)");
            statement.execute("insert into customers values "
                    + "(5, 'Erin', 'erin@example.com', true, "
                    + "'2026-01-19 18:20:00', '2000-12-31', 42.42)");
            statement.execute("create view customer_names as "
                    + "select id, name from customers");
            statement.execute("create view customer_emails as "
                    + "select id, email from customers");
            statement.execute("create table composite ("
                    + "a bigint, b bigint, primary key (a, b))");
            statement.execute("create table no_key (id bigint, label varchar(20))");
            statement.execute("create table binaries ("
                    + "id bigint primary key, payload varbinary(100), note varchar(20))");
            statement.execute("insert into binaries values (1, X'616263', 'x')");
            statement.execute("create table widgets ("
                    + "code varchar(20) primary key, label varchar(50))");
            statement.execute("insert into widgets values ('w-1', 'Widget One')");
            statement.execute("create table docs ("
                    + "id uuid primary key, title varchar(50))");
            statement.execute("insert into docs values "
                    + "('550e8400-e29b-41d4-a716-446655440000', 'Doc')");
        }
    }
}
