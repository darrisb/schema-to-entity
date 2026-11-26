package com.example.dynamicmeta.data;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.drift.SchemaSnapshot;
import com.example.dynamicmeta.drift.SchemaVersion;
import com.example.dynamicmeta.drift.SnapshotBuilder;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DR-008: every successful data response names the schema version it served,
 * as the X-Schema-Version header and (for the entity listing) as a
 * {@code schemaVersion} field on each entity descriptor, so the listing keeps
 * its historical bare-array shape. The version is the leading characters of
 * the snapshot fingerprint of the cached schema, so it changes only when the
 * cache starts serving a different structure, and CORS exposes it to
 * browsers.
 */
class SchemaVersionHeaderTests {

    private static final String DATABASE = "ste_schema_version_header";
    private static final String JDBC_URL =
            "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1";
    private static final String HEADER = DataController.SCHEMA_VERSION_HEADER;
    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    private SourceRegistry registry;
    private SchemaCache cache;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        seed("create table customers (id bigint primary key, name varchar(80) not null)",
                "insert into customers values (1, 'Alice'), (2, 'Bob')");
        buildMockMvc();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
    }

    @Test
    void listPageAndDetailAllCarryTheVersionAndItMatchesTheListingJson() throws Exception {
        MvcResult listing = mockMvc.perform(get("/api/data/orders"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HEADER))
                .andExpect(header().string(HEADER, matchesPattern("[0-9a-f]{12}")))
                .andReturn();
        String version = listing.getResponse().getHeader(HEADER);

        // The listing is still a bare array whose descriptors each carry the
        // same value as the header (no response-shape change for old clients).
        mockMvc.perform(get("/api/data/orders"))
                .andExpect(jsonPath("$[0].name").value("CUSTOMERS"))
                .andExpect(jsonPath("$[0].schemaVersion").value(version));

        // The version is the fingerprint prefix the drift snapshot of the
        // cached schema produces, so it is the snapshot identity, not a hash
        // of something else.
        SchemaSnapshot snapshot =
                new SnapshotBuilder().build("orders", cache.get("orders"), Instant.now());
        assertThat(version)
                .isEqualTo(snapshot.fingerprint().substring(0, SchemaVersion.LENGTH))
                .isEqualTo(SchemaVersion.truncate(snapshot.fingerprint()));

        // A page response: header only, the {items,total,limit,offset} envelope
        // is unchanged.
        mockMvc.perform(get("/api/data/orders/customers").param("sort", "id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, version))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").exists())
                .andExpect(jsonPath("$.schemaVersion").doesNotExist());

        // A row is untouched too; only the header names the version.
        mockMvc.perform(get("/api/data/orders/customers/1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, version))
                .andExpect(jsonPath("$.ID").value(1))
                .andExpect(jsonPath("$.NAME").value("Alice"))
                .andExpect(jsonPath("$.schemaVersion").doesNotExist());
    }

    @Test
    void versionChangesOnlyWhenTheCachedSchemaIsRefreshed() throws Exception {
        String before = mockMvc.perform(get("/api/data/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HEADER);

        // The structure changed in the database, but the cache still serves the
        // old schema, so the published version must not move.
        seedKeepingData("alter table customers add column note varchar(20)");
        mockMvc.perform(get("/api/data/orders"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, before));

        // Forcing rediscovery replaces the cache entry, and the version of the
        // new entry differs and matches the new snapshot's fingerprint.
        cache.refresh("orders");
        SchemaSnapshot after =
                new SnapshotBuilder().build("orders", cache.get("orders"), Instant.now());
        mockMvc.perform(get("/api/data/orders"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER,
                        after.fingerprint().substring(0, SchemaVersion.LENGTH)))
                .andExpect(jsonPath("$[0].schemaVersion").value(after
                        .fingerprint().substring(0, SchemaVersion.LENGTH)));
        mockMvc.perform(get("/api/data/orders/customers"))
                .andExpect(header().string(HEADER,
                        after.fingerprint().substring(0, SchemaVersion.LENGTH)));
    }

    @Test
    void corsActualResponseExposesTheSchemaVersionHeader() {
        String allowedOrigin = "https://app.example.com";
        String corsDatabase = "ste_schema_version_cors";
        try {
            seedDatabase(corsDatabase,
                    "create table customers (id bigint primary key, name varchar(80))",
                    "insert into customers values (1, 'Alice')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        JdbcDataSource host = new JdbcDataSource();
        host.setURL("jdbc:h2:mem:ste_schema_version_cors_host;DB_CLOSE_DELAY=-1");
        host.setUser("sa");
        host.setPassword("");

        List<String> properties = new ArrayList<>(List.of(
                "schema-to-entity.data-api-enabled=true",
                "schema-to-entity.allow-embedded-databases=true",
                "schema-to-entity.data-allowed-origins=" + allowedOrigin,
                "schema-to-entity.sources.orders.url="
                        + "jdbc:h2:mem:" + corsDatabase + ";DB_CLOSE_DELAY=-1",
                "schema-to-entity.sources.orders.username=sa",
                "schema-to-entity.sources.orders.schema=PUBLIC"));

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class, SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> host)
                .withPropertyValues(properties.toArray(new String[0]))
                .run(context -> {
                    MockMvc webMockMvc =
                            MockMvcBuilders.webAppContextSetup(context).build();
                    MockHttpServletResponse response = webMockMvc.perform(
                                    get("/api/data/orders")
                                            .header(HttpHeaders.ORIGIN, allowedOrigin))
                            .andExpect(status().isOk())
                            .andExpect(header().string(
                                    HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HEADER))
                            .andExpect(header().exists(HEADER))
                            .andReturn().getResponse();
                    assertThat(response.getHeader(HEADER).length()).isEqualTo(SchemaVersion.LENGTH);
                });
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private void buildMockMvc() {
        SourceProperties source = new SourceProperties();
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");

        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));

        Map<String, DataSourceDefinition> definitions =
                DataSourceDefinitions.from(properties, PARSER, POLICY);
        registry = new SourceRegistry(definitions, FACTORY, PARSER, Duration.ofSeconds(10));
        SchemaDiscoveryService discovery =
                new SchemaDiscoveryService(null, properties, POLICY, FACTORY);
        cache = new SchemaCache(registry, discovery, properties);
        QueryParameterParser parser = new QueryParameterParser(properties);
        DataQueryService service = new DataQueryService(registry, cache, parser, properties);

        mockMvc = MockMvcBuilders.standaloneSetup(new DataController(service)).build();
    }

    private static void seed(String... statements) throws Exception {
        seedDatabase(DATABASE, statements);
    }

    /** DDL against the already-seeded database without dropping its contents. */
    private static void seedKeepingData(String... statements) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void seedDatabase(String database, String... statements) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
