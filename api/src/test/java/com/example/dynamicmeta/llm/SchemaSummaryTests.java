package com.example.dynamicmeta.llm;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.data.DataSourceDefinitions;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.graph.GraphIds;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaSummaryTests {

    private static final String CRM_URL = "jdbc:h2:mem:ste_llm_crm;DB_CLOSE_DELAY=-1";
    private static final String COMMERCE_URL = "jdbc:h2:mem:ste_llm_commerce;DB_CLOSE_DELAY=-1";
    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    @TempDir
    Path tempDir;

    private SourceRegistry registry;
    private OverlayStore overlay;
    private SchemaSummary summary;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        seed();
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of(
                "crm", source(CRM_URL, List.of("customers.secret_note")),
                "commerce", source(COMMERCE_URL, List.of())));
        registry = new SourceRegistry(DataSourceDefinitions.from(properties, PARSER, POLICY),
                FACTORY, PARSER, Duration.ofSeconds(10));
        SchemaDiscoveryService discovery = new SchemaDiscoveryService(null, properties,
                POLICY, FACTORY);
        SchemaCache cache = new SchemaCache(registry, discovery, properties);
        SchemaGraphBuilder graph = new SchemaGraphBuilder(registry, cache);
        overlay = new OverlayStore(tempDir.resolve("overlay.json"));
        summary = new SchemaSummary(graph, overlay, cache, registry, properties);
        mvc = MockMvcBuilders.standaloneSetup(new LlmController(summary)).build();
    }

    @AfterEach
    void close() {
        if (registry != null) registry.destroy();
    }

    @Test
    void listsEntitiesColumnsAndOnlyDeclaredOrConfirmedLinks() {
        String proposal = GraphIds.relationshipId("commerce:orders.customer_id", "crm:customers.id");
        overlay.decide(proposal, "confirmed");
        overlay.setLabel("crm:customers", "Customers", "CRM accounts");

        String text = summary.text(summary.summarize(null, false));

        assertThat(text).contains("commerce:orders (table, key ID)")
                .contains("crm:customers (table, key ID) - Customers: CRM accounts")
                .contains("columns: ID NUMBER")
                .contains("id <- commerce:orders.customer_id")
                .doesNotContain("SECRET_NOTE");
    }

    @Test
    void proposedRelationshipAndHiddenColumnDoNotAppear() {
        String text = summary.text(summary.summarize(null, false));

        assertThat(text).contains("crm:customers", "commerce:orders")
                .doesNotContain("commerce:orders.customer_id")
                .doesNotContain("SECRET_NOTE");
    }

    @Test
    void valuesListsOnlyLowCardinalityTextValues() throws Exception {
        mvc.perform(get("/api/llm/schema").param("values", "true"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("STATUS TEXT [values: NEW | PAID]")
                        .doesNotContain("values: customer-"));

        mvc.perform(get("/api/llm/schema").param("format", "json").param("values", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities[0].id").value("commerce:orders"))
                .andExpect(jsonPath("$.entities[0].columns[2].values.length()").value(2));
    }

    private static SourceProperties source(String url, List<String> hidden) {
        SourceProperties source = new SourceProperties();
        source.setUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        source.setHiddenColumns(hidden);
        return source;
    }

    private static void seed() throws Exception {
        JdbcDataSource crm = dataSource(CRM_URL);
        try (Connection connection = crm.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("create table customers (id bigint primary key, name varchar(80), secret_note varchar(80))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into customers values (?, ?, ?)") ) {
                for (int i = 1; i <= 21; i++) {
                    insert.setLong(1, i);
                    insert.setString(2, "customer-" + i);
                    insert.setString(3, "hidden-" + i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        JdbcDataSource commerce = dataSource(COMMERCE_URL);
        try (Connection connection = commerce.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("create table orders (id bigint primary key, customer_id bigint, status varchar(20))");
            statement.execute("insert into orders values (1, 1, 'NEW'), (2, 2, 'PAID'), (3, 3, 'NEW')");
        }
    }

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        source.setPassword("");
        return source;
    }
}
