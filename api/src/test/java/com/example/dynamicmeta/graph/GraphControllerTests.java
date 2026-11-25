package com.example.dynamicmeta.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GR-007: the graph endpoints over two real H2 sources. The proposal
 * between them is the cross-source inference of a {@code customer_id}
 * column; everything else is a declared foreign key. The overlay edits are
 * written to a temporary file, the only file these endpoints write.
 */
class GraphControllerTests {

    /** The proposal of {@code billing.invoices.customer_id} to {@code sales.customers.id}. */
    private static final String PROPOSAL_ID =
            "billing:invoices.customer_id->sales:customers.id";

    /** The foreign key {@code sales.orders.customer_id} references {@code customers.id}. */
    private static final String DECLARED_ID =
            "sales:orders.customer_id->sales:customers.id";

    private static final String SALES_URL = "jdbc:h2:mem:ste_graph_sales;DB_CLOSE_DELAY=-1";
    private static final String BILLING_URL = "jdbc:h2:mem:ste_graph_billing;DB_CLOSE_DELAY=-1";
    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    private static final String ALLOWED_ORIGIN = "https://app.example.com";

    @TempDir
    Path tempDir;

    private SourceRegistry registry;
    private OverlayStore overlayStore;
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
    void graphReturnsBothSourcesAndTheProposedCrossSourceRelationship() throws Exception {
        mockMvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specVersion").value("1.0"))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].id").value("billing"))
                .andExpect(jsonPath("$.sources[1].id").value("sales"))
                .andExpect(jsonPath("$.sources[*].status").value(hasItem("ok")))
                .andExpect(jsonPath("$.entities[*].id").value(hasItem("billing:invoices")))
                .andExpect(jsonPath("$.entities[*].id").value(hasItem("sales:customers")))
                .andExpect(jsonPath("$.entities[*].id").value(hasItem("sales:orders")))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + PROPOSAL_ID + "')].status")
                        .value(hasItem("proposed")))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + PROPOSAL_ID + "')].scope")
                        .value(hasItem("cross-source")))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + DECLARED_ID + "')].status")
                        .value(hasItem("declared")));
    }

    @Test
    void sourcesParameterLimitsTheDocument() throws Exception {
        mockMvc.perform(get("/api/graph").param("sources", "sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].id").value("sales"))
                // Inference only matches names across the included sources.
                .andExpect(jsonPath("$.relationships.length()").value(1))
                .andExpect(jsonPath("$.relationships[0].id").value(DECLARED_ID));
    }

    @Test
    void unknownSourceNameReturns404() throws Exception {
        mockMvc.perform(get("/api/graph").param("sources", "sales,nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown data source: nope"));
        mockMvc.perform(get("/api/graph/relationships").param("sources", "nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void relationshipListingIsFilteredByStatus() throws Exception {
        mockMvc.perform(get("/api/graph/relationships"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/graph/relationships").param("status", "proposed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(PROPOSAL_ID))
                .andExpect(jsonPath("$[0].confidence").value(0.8));

        mockMvc.perform(get("/api/graph/relationships").param("status", "declared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(DECLARED_ID));

        mockMvc.perform(get("/api/graph/relationships").param("status", "confirmed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/graph/relationships").param("status", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("status")));
    }

    @Test
    void confirmingAndRejectingChangeTheNextDocument() throws Exception {
        decide(PROPOSAL_ID, "confirmed");

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + PROPOSAL_ID + "')].status")
                        .value(hasItem("confirmed")));

        // Rejecting hides the relationship from the default document.
        decide(PROPOSAL_ID, "rejected");

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + PROPOSAL_ID + "')].id").isEmpty())
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + DECLARED_ID + "')].id")
                        .value(hasItem(DECLARED_ID)));

        mockMvc.perform(get("/api/graph/relationships").param("status", "proposed"))
                .andExpect(jsonPath("$.length()").value(0));

        // But it is still there for whoever asks, and the decision can be
        // cleared again.
        mockMvc.perform(get("/api/graph").param("includeRejected", "true"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + PROPOSAL_ID + "')].status")
                        .value(hasItem("rejected")));

        mockMvc.perform(get("/api/graph/relationships").param("status", "rejected"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(PROPOSAL_ID));

        decide(PROPOSAL_ID, null);

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + PROPOSAL_ID + "')].status")
                        .value(hasItem("proposed")));
    }

    @Test
    void manualRelationshipAndLabelRoundTrip() throws Exception {
        String manualId = "sales:orders.id->billing:invoices.id";

        mockMvc.perform(post("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:orders.id", "to": "billing:invoices.id"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(manualId));

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + manualId + "')].origin")
                        .value(hasItem("manual")))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + manualId + "')].status")
                        .value(hasItem("confirmed")))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + manualId + "')].scope")
                        .value(hasItem("cross-source")));

        mockMvc.perform(put("/api/graph/overlay/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "sales:customers",
                                 "label": "Customers",
                                 "description": "Billable customers"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/graph/overlay/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "sales:customers.name", "label": "Customer name"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.entities[?(@.id == 'sales:customers')].label")
                        .value(hasItem("Customers")))
                .andExpect(jsonPath(
                        "$.entities[?(@.id == 'sales:customers')].description")
                        .value(hasItem("Billable customers")))
                .andExpect(jsonPath("$.entities[?(@.id == 'sales:customers')]"
                                + ".columns[?(@.id == 'sales:customers.name')].label")
                        .value(hasItem("Customer name")));

        mockMvc.perform(delete("/api/graph/overlay/labels")
                        .param("id", "sales:customers.name"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath("$.entities[?(@.id == 'sales:customers')]"
                                + ".columns[?(@.id == 'sales:customers.name')].label")
                        .value(hasItem(nullValue())));

        mockMvc.perform(delete("/api/graph/overlay/relationships")
                        .param("id", manualId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + manualId + "')].id").isEmpty());
    }

    @Test
    void manualRelationshipCanBeDecided() throws Exception {
        String manualId = "sales:orders.id->billing:invoices.id";

        mockMvc.perform(post("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:orders.id", "to": "billing:invoices.id"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(manualId));

        // A manual relationship is in the document, so a decision names it
        // and is stored. What a decision cannot change is the document's
        // status: a manual relationship is its own confirmation. So the
        // saved decision shows in the overlay, and the document shows the
        // decision matched by keeping the id out of the orphans.
        decide(manualId, "confirmed");

        assertThat(overlayStore.overlay().relationshipDecisions())
                .containsEntry(manualId, "confirmed");

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + manualId + "')].status")
                        .value(hasItem("confirmed")))
                .andExpect(jsonPath("$.orphans.length()").value(0));

        // The decision can be cleared again.
        decide(manualId, null);

        assertThat(overlayStore.overlay().relationshipDecisions())
                .doesNotContainKey(manualId);

        mockMvc.perform(get("/api/graph"))
                .andExpect(jsonPath(
                        "$.relationships[?(@.id == '" + manualId + "')].status")
                        .value(hasItem("confirmed")));
    }

    @Test
    void unknownIdsReturn404() throws Exception {
        mockMvc.perform(put("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "sales:missing.x->sales:customers.id",
                                 "status": "confirmed"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("sales:missing.x")));

        mockMvc.perform(delete("/api/graph/overlay/relationships")
                        .param("id", DECLARED_ID))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:missing.x", "to": "billing:invoices.id"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("sales:missing.x")));

        mockMvc.perform(put("/api/graph/overlay/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "sales:missing", "label": "Nope"}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/graph/overlay/labels")
                        .param("id", "sales:missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputReturns400() throws Exception {
        mockMvc.perform(put("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "%s", "status": "maybe"}""".formatted(PROPOSAL_ID)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/graph/overlay/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "sales:customers", "label": "  "}"""))
                .andExpect(status().isBadRequest());

        // An entity id is not a column id.
        mockMvc.perform(post("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from": "sales:customers", "to": "billing:invoices.id"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void graphEndpointsSendNoCorsHeaders() {
        corsRunner("schema-to-entity.data-allowed-origins=" + ALLOWED_ORIGIN)
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/api/graph")
                                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                            .andExpect(status().isOk())
                            .andExpect(header().doesNotExist(
                                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

                    mvc.perform(options("/api/graph")
                                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                            "GET"))
                            .andExpect(status().is4xxClientError())
                            .andExpect(header().doesNotExist(
                                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
                });
    }

    @Test
    void graphBeansRegisteredOnlyWhenGraphAndDataApiAreEnabled() {
        graphRunner().run(context -> {
            assertThat(context).hasSingleBean(GraphController.class);
            assertThat(context).hasSingleBean(OverlayStore.class);
            assertThat(context).hasSingleBean(SchemaGraphBuilder.class);
        });

        graphRunner("schema-to-entity.graph-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(GraphController.class));

        graphRunner("schema-to-entity.data-api-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(GraphController.class));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private void decide(String id, String status) throws Exception {
        String body = status == null
                ? "{\"id\": \"" + id + "\", \"status\": null}"
                : "{\"id\": \"" + id + "\", \"status\": \"" + status + "\"}";
        mockMvc.perform(put("/api/graph/overlay/relationships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

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
        overlayStore = new OverlayStore(tempDir.resolve("graph-overlay.json"));

        return MockMvcBuilders.standaloneSetup(
                new GraphController(builder, overlayStore)).build();
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
        JdbcDataSource sales = new JdbcDataSource();
        sales.setURL(SALES_URL);
        sales.setUser("sa");
        sales.setPassword("");
        try (Connection connection = sales.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("create table customers ("
                    + "id bigint primary key, name varchar(80) not null)");
            statement.execute("create table orders ("
                    + "id bigint primary key, customer_id bigint, "
                    + "foreign key (customer_id) references customers(id))");
        }

        JdbcDataSource billing = new JdbcDataSource();
        billing.setURL(BILLING_URL);
        billing.setUser("sa");
        billing.setPassword("");
        try (Connection connection = billing.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("create table invoices ("
                    + "id bigint primary key, customer_id bigint, "
                    + "amount numeric(10,2))");
        }
    }

    /** A servlet context with both sources and the graph enabled. */
    private WebApplicationContextRunner graphRunner(String... extraProperties) {
        return runner(List.of(
                "schema-to-entity.graph-enabled=true",
                "schema-to-entity.data-api-enabled=true",
                "schema-to-entity.allow-embedded-databases=true",
                "schema-to-entity.sources.sales.url=" + SALES_URL,
                "schema-to-entity.sources.sales.username=sa",
                "schema-to-entity.sources.billing.url=" + BILLING_URL,
                "schema-to-entity.sources.billing.username=sa",
                "schema-to-entity.overlay-file=" + runnerOverlayFile()),
                List.of(extraProperties));
    }

    /** The same, for checking that the CORS mapping stops at /api/data. */
    private WebApplicationContextRunner corsRunner(String... extraProperties) {
        return graphRunner(extraProperties);
    }

    private String runnerOverlayFile() {
        try {
            return Files.createTempDirectory("ste-graph-runner")
                    .resolve("overlay.json").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WebApplicationContextRunner runner(List<String> properties,
                                               List<String> extraProperties) {
        JdbcDataSource host = new JdbcDataSource();
        host.setURL("jdbc:h2:mem:ste_graph_host;DB_CLOSE_DELAY=-1");
        host.setUser("sa");
        host.setPassword("");

        List<String> all = new java.util.ArrayList<>(properties);
        all.addAll(extraProperties);

        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> host)
                .withPropertyValues(all.toArray(new String[0]));
    }
}
