package com.example.dynamicmeta.drift;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.data.DataSourceDefinition;
import com.example.dynamicmeta.data.DataSourceDefinitions;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.graph.GraphController;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DR-007: the drift endpoints over real H2 sources. A read of a source that
 * has never been checked records its baseline, {@code ?check=true} reports
 * drift an {@code ALTER TABLE} caused, acceptance is refused for a fingerprint
 * the last check did not show, and a rename moves overlay edits to the new id
 * — the only files these tests write are snapshots and the overlay in a
 * temporary folder. The graph endpoints are mounted alongside so a rename can
 * be seen in the served document.
 */
class DriftControllerTests {

    private static final String SALES_URL = "jdbc:h2:mem:ste_driftctl_sales;DB_CLOSE_DELAY=-1";
    private static final String BILLING_URL = "jdbc:h2:mem:ste_driftctl_billing;DB_CLOSE_DELAY=-1";

    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    private static final String ALLOWED_ORIGIN = "https://app.example.com";

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDir;

    private SourceRegistry registry;
    private OverlayStore overlayStore;
    private Path snapshotDir;
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
    void firstReadRecordsTheBaselineAndAChangedSchemaIsReported() throws Exception {
        mockMvc.perform(get("/api/graph/drift/sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("sales"))
                .andExpect(jsonPath("$.status").value("baseline-created"))
                .andExpect(jsonPath("$.baselineFingerprint").isString())
                .andExpect(jsonPath("$.changes.length()").value(0));
        assertThat(snapshotDir.resolve("sales.json")).exists();

        alter("alter table customers add note varchar(40)");

        mockMvc.perform(get("/api/graph/drift/sales").param("check", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("changed"))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].type").value("COLUMN_ADDED"))
                .andExpect(jsonPath("$.changes[0].entity").value("CUSTOMERS"))
                .andExpect(jsonPath("$.changes[0].column").value("NOTE"))
                // One impact entry per change, and a fresh check of a changed
                // report keeps its entries.
                .andExpect(jsonPath("$.impact.length()").value(1))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void theListingServesRememberedReportsAndChecksSourcesNeverChecked() throws Exception {
        mockMvc.perform(get("/api/graph/drift/sales"))
                .andExpect(status().isOk());

        alter("alter table customers add note varchar(40)");
        mockMvc.perform(get("/api/graph/drift/sales").param("check", "true"))
                .andExpect(status().isOk());
        assertThat(snapshotDir.resolve("billing.json")).doesNotExist();

        // In name order. Billing has never been checked, so the listing
        // checks it now and its first report records the baseline; sales
        // serves the remembered changed report without another check.
        mockMvc.perform(get("/api/graph/drift"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].source").value("billing"))
                .andExpect(jsonPath("$[0].status").value("baseline-created"))
                .andExpect(jsonPath("$[1].source").value("sales"))
                .andExpect(jsonPath("$[1].status").value("changed"));
        assertThat(snapshotDir.resolve("billing.json")).exists();
    }

    @Test
    void acceptingTheReportedFingerprintReturnsTheUnchangedReport() throws Exception {
        String baselineFingerprint = fingerprintOf(
                mockMvc.perform(get("/api/graph/drift/sales"))
                        .andExpect(status().isOk())
                        .andReturn());

        alter("alter table customers add note varchar(40)");
        JsonNode changed = nodeOf(mockMvc.perform(
                        get("/api/graph/drift/sales").param("check", "true"))
                        .andExpect(status().isOk())
                        .andReturn());
        String currentFingerprint = changed.path("currentFingerprint").asText();
        assertThat(currentFingerprint).isNotEqualTo(baselineFingerprint);

        // The fingerprint of the check before this one is stale: the schema
        // moved since the reviewer read that report.
        mockMvc.perform(post("/api/graph/drift/sales/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\": \"%s\"}".formatted(baselineFingerprint)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("changed since it was checked")));

        mockMvc.perform(post("/api/graph/drift/sales/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\": \"%s\"}".formatted(currentFingerprint)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"))
                .andExpect(jsonPath("$.baselineFingerprint").value(currentFingerprint))
                .andExpect(jsonPath("$.changes.length()").value(0));

        // The accepted drift is the remembered report now, and the baseline
        // on disk is the accepted snapshot.
        mockMvc.perform(get("/api/graph/drift/sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"));
        assertThat(Files.readString(snapshotDir.resolve("sales.json")))
                .contains(currentFingerprint);
    }

    @Test
    void acceptingBeforeAnyCheckIsRefusedAndWritesNothing() throws Exception {
        mockMvc.perform(post("/api/graph/drift/billing/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\": \"%s\"}".formatted("0".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("has not been checked")));
        assertThat(snapshotDir.resolve("billing.json")).doesNotExist();
    }

    @Test
    void unknownSourcesReturn404AndNeverWriteAnything() throws Exception {
        mockMvc.perform(get("/api/graph/drift/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown data source: nope"));

        mockMvc.perform(get("/api/graph/drift/nope").param("check", "true"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/graph/drift/nope/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\": \"%s\"}".formatted("0".repeat(64))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/graph/drift/nope/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": \"nope:orders\", \"to\": \"nope:purchases\"}"))
                .andExpect(status().isNotFound());

        assertThat(snapshotDir).doesNotExist();
        assertThat(overlayStore.overlay().labels()).isEmpty();
    }

    @Test
    void aConfirmedRenameMovesTheLabelToTheNewId() throws Exception {
        mockMvc.perform(put("/api/graph/overlay/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "sales:orders", "label": "Purchases"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:orders", "to": "sales:customers"}"""))
                .andExpect(status().isNoContent());

        // The edit followed the id into the served document.
        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath("$.entities[?(@.id == 'sales:customers')].label")
                        .value(hasItem("Purchases")))
                .andExpect(jsonPath("$.entities[?(@.id == 'sales:orders')].label")
                        .value(hasItem(nullValue())));

        assertThat(overlayStore.overlay().labels()).containsKey("sales:customers");
        assertThat(overlayStore.overlay().labels()).doesNotContainKey("sales:orders");
    }

    @Test
    void renameIdsMustBeEntityOrColumnIdsOfTheNamedSource() throws Exception {
        // An id of another source.
        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:orders", "to": "billing:invoices"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("must belong to source 'sales'")));

        // Not an id at all.
        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "salesorders", "to": "sales:customers"}"""))
                .andExpect(status().isBadRequest());

        // A relationship id is not an entity or column id.
        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:orders.customer_id->sales:customers.id",
                                 "to": "sales:orders.id"}"""))
                .andExpect(status().isBadRequest());

        // A missing end.
        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("from")));

        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": \"  \", \"to\": \"sales:customers\"}"))
                .andExpect(status().isBadRequest());

        // Column ids of the source are as usable as entity ids.
        mockMvc.perform(post("/api/graph/drift/sales/renames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:customers.name",
                                 "to": "sales:customers.full_name"}"""))
                .andExpect(status().isNoContent());
    }

    @Test
    void fingerprintMustBePresentToAccept() throws Exception {
        mockMvc.perform(get("/api/graph/drift/sales")).andExpect(status().isOk());

        mockMvc.perform(post("/api/graph/drift/sales/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("fingerprint")));
    }

    @Test
    void driftEndpointsSendNoCorsHeaders() {
        runner("schema-to-entity.data-allowed-origins=" + ALLOWED_ORIGIN)
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/api/graph/drift/nope")
                                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                            .andExpect(status().isNotFound())
                            .andExpect(header().doesNotExist(
                                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

                    mvc.perform(options("/api/graph/drift")
                                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                            "GET"))
                            .andExpect(status().is4xxClientError())
                            .andExpect(header().doesNotExist(
                                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
                });
    }

    @Test
    void driftEndpointsComeWithTheGraphAndTheDataApiOnly() {
        runner().run(context -> assertThat(context).hasSingleBean(DriftController.class));

        runner("schema-to-entity.graph-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DriftController.class));

        runner("schema-to-entity.data-api-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DriftController.class));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private MockMvc buildMockMvc() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of(
                "sales", source(SALES_URL),
                "billing", source(BILLING_URL)));

        Map<String, DataSourceDefinition> definitions =
                DataSourceDefinitions.from(properties, PARSER, POLICY);
        registry = new SourceRegistry(definitions, FACTORY, PARSER, Duration.ofSeconds(10));
        SchemaDiscoveryService discovery =
                new SchemaDiscoveryService(null, properties, POLICY, FACTORY);
        SchemaCache cache = new SchemaCache(registry, discovery, properties);
        SchemaGraphBuilder builder = new SchemaGraphBuilder(registry, cache);
        overlayStore = new OverlayStore(tempDir.resolve("drift-overlay.json"));
        snapshotDir = tempDir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(snapshotDir);
        DriftService service = new DriftService(registry, cache, new SnapshotBuilder(),
                store, overlayStore, Clock.systemUTC());

        return MockMvcBuilders.standaloneSetup(
                new DriftController(service, registry, overlayStore),
                new GraphController(builder, overlayStore)).build();
    }

    /** The {@code currentFingerprint} of a report the caller just read. */
    private String fingerprintOf(MvcResult result) throws Exception {
        return nodeOf(result).path("currentFingerprint").asText();
    }

    private JsonNode nodeOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static SourceProperties source(String url) {
        SourceProperties source = new SourceProperties();
        source.setUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        return source;
    }

    private static void seed() throws Exception {
        execute(SALES_URL,
                "DROP ALL OBJECTS",
                "create table customers (id bigint primary key, name varchar(80) not null)",
                "create table orders (id bigint primary key, customer_id bigint, "
                        + "foreign key (customer_id) references customers(id))");
        execute(BILLING_URL,
                "DROP ALL OBJECTS",
                "create table invoices (id bigint primary key, customer_id bigint, "
                        + "amount numeric(10,2))");
    }

    /** Runs DDL straight against one H2 source; the endpoints never do this. */
    private void alter(String... statements) throws Exception {
        execute(SALES_URL, statements);
    }

    private static void execute(String url, String... statements) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** A servlet context with both sources, the graph and the drift endpoints. */
    private WebApplicationContextRunner runner(String... extraProperties) {
        Path runnerTemp = createRunnerTempDir();
        java.util.List<String> all = new java.util.ArrayList<>(List.of(
                "schema-to-entity.graph-enabled=true",
                "schema-to-entity.data-api-enabled=true",
                "schema-to-entity.allow-embedded-databases=true",
                "schema-to-entity.sources.sales.url=" + SALES_URL,
                "schema-to-entity.sources.sales.username=sa",
                "schema-to-entity.sources.billing.url=" + BILLING_URL,
                "schema-to-entity.sources.billing.username=sa",
                "schema-to-entity.overlay-file=" + runnerTemp.resolve("overlay.json"),
                "schema-to-entity.snapshot-dir=" + runnerTemp.resolve("snapshots")));
        all.addAll(List.of(extraProperties));

        JdbcDataSource host = new JdbcDataSource();
        host.setURL("jdbc:h2:mem:ste_driftctl_host;DB_CLOSE_DELAY=-1");
        host.setUser("sa");
        host.setPassword("");

        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> host)
                .withPropertyValues(all.toArray(new String[0]));
    }

    private static Path createRunnerTempDir() {
        try {
            return Files.createTempDirectory("ste-drift-runner");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
