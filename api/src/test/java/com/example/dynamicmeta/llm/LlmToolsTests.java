package com.example.dynamicmeta.llm;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.data.DataQueryService;
import com.example.dynamicmeta.data.DataSourceDefinitions;
import com.example.dynamicmeta.data.QueryParameterParser;
import com.example.dynamicmeta.data.RelationshipExpander;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmToolsTests {

    private static final String CRM = "jdbc:h2:mem:llm_tools_crm;DB_CLOSE_DELAY=-1";
    private static final String SALES = "jdbc:h2:mem:llm_tools_sales;DB_CLOSE_DELAY=-1";

    @TempDir Path tempDir;
    private SourceRegistry registry;
    private LlmTools tools;
    private SchemaGraphBuilder graph;
    private DataQueryService queries;
    private SchemaSummary summary;

    @BeforeEach
    void setUp() throws Exception {
        execute(CRM, "drop all objects",
                "create table customers (id bigint primary key, name varchar(1000))",
                "insert into customers values (1, repeat('x', 600)), (2, 'short')");
        execute(SALES, "drop all objects",
                "create table accounts (id bigint primary key, name varchar(40))",
                "create table orders (id bigint primary key, account_id bigint, status varchar(20), total decimal(10,2), constraint fk_account foreign key (account_id) references accounts(id))",
                "insert into accounts values (1, 'Acme')",
                "insert into orders values (10, 1, 'PAID', 12.50), (11, 1, 'NEW', 7.50)");

        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setGraphEnabled(true);
        properties.setDefaultPageSize(20);
        properties.setMaxPageSize(100);
        properties.setSources(Map.of("crm", source(CRM), "sales", source(SALES)));
        ConnectionStringParser parser = new ConnectionStringParser();
        ConnectionPolicy policy = new ConnectionPolicy(List.of(), true);
        JdbcConnectionFactory factory = new JdbcConnectionFactory(Duration.ofSeconds(5), Duration.ofSeconds(5));
        registry = new SourceRegistry(DataSourceDefinitions.from(properties, parser, policy),
                factory, parser, Duration.ofSeconds(5));
        SchemaCache cache = new SchemaCache(registry,
                new SchemaDiscoveryService(null, properties, policy, factory), properties);
        graph = new SchemaGraphBuilder(registry, cache);
        OverlayStore overlay = new OverlayStore(tempDir.resolve("overlay.json"));
        QueryParameterParser queryParser = new QueryParameterParser(properties);
        queries = new DataQueryService(registry, cache, queryParser, properties,
                () -> new RelationshipExpander(graph, overlay));
        summary = new SchemaSummary(graph, overlay, cache, registry, properties);
        tools = new LlmTools(graph, overlay, queries, summary);
    }

    @AfterEach void close() { if (registry != null) registry.destroy(); }

    @Test
    void exposesEveryOperationAcrossTwoSources() {
        assertThat((List<Map<String, Object>>) tools.listSources())
                .extracting(source -> source.get("name"))
                .containsExactly("crm", "sales");

        Map<String, Object> description = tools.describeEntity("sales:orders");
        assertThat(description).containsEntry("id", "sales:orders");
        assertThat((List<?>) description.get("columns")).isNotEmpty();
        assertThat((List<?>) description.get("relationships")).isNotEmpty();
        assertThat((List<Map<String, Object>>) tools.findRelated("sales:orders"))
                .anySatisfy(relationship -> assertThat(relationship)
                        .containsEntry("entityId", "sales:accounts")
                        .containsEntry("direction", "outgoing")
                        .containsEntry("fromColumn", "account_id")
                        .containsEntry("toColumn", "id"));

        Map<String, Object> page = tools.queryEntity("sales:orders",
                Map.of("eq.status", "PAID", "gte.total", "10"),
                List.of("id", "status", "total"), List.of("-id"), null, List.of());
        assertThat(page).containsEntry("total", 1L).containsEntry("limit", 20);
        assertThat((List<?>) page.get("items")).hasSize(1);

        Map<String, Object> aggregate = tools.aggregate("sales:orders", List.of("status"),
                List.of("count", "sum:total"), Map.of(), List.of("-count"), 20);
        assertThat((List<?>) aggregate.get("rows")).hasSize(2);

        assertThat(tools.schemaSummary(java.util.Set.of("sales"), false).entities())
                .extracting(SchemaSummary.Entity::id)
                .containsExactly("sales:accounts", "sales:orders");
    }

    @Test
    void returnsErrorsAndTruncatesLongText() {
        assertThat(tools.describeEntity("sales:missing")).containsKey("error");
        assertThat(tools.queryEntity("sales:orders", Map.of("eq.missing", "x"),
                List.of(), List.of(), 20, List.of())).containsKey("error");

        Map<String, Object> page = tools.queryEntity("crm:customers", Map.of("eq.id", "1"),
                List.of("name"), List.of(), 20, List.of());
        @SuppressWarnings("unchecked")
        String name = (String) ((List<Map<String, Object>>) page.get("items")).getFirst().get("NAME");
        assertThat(name).hasSize(500).endsWith("…");
    }

    @Test
    void listSourcesReturnsErrorWhenOverlayStoreFails() {
        OverlayStore failingOverlay = mock(OverlayStore.class);
        when(failingOverlay.overlay()).thenThrow(new IllegalStateException("overlay failed"));

        assertThat(new LlmTools(graph, failingOverlay, queries, summary).listSources())
                .isEqualTo(Map.of("error", "Database query failed"));
    }

    @Test
    void dataOperationsReturnConsistentErrorsForInvalidEntityIds() {
        for (String entityId : new String[] {"sales:missing", "malformed", null}) {
            String expected = entityId == null || "malformed".equals(entityId)
                    ? "entityId must look like source:entity"
                    : "Unknown entity: sales:missing";
            assertThat(tools.queryEntity(entityId, Map.of(), List.of(), List.of(), 20,
                    List.of())).containsEntry("error", expected);
            assertThat(tools.aggregate(entityId, List.of(), List.of("count"), Map.of(),
                    List.of(), 20)).containsEntry("error", expected);
            assertThat((Map<String, Object>) tools.findRelated(entityId))
                    .containsEntry("error", expected);
        }
    }

    @Test
    void rejectsLimitsOutsideToolBounds() {
        for (int limit : new int[] {0, 101}) {
            assertThat(tools.queryEntity("sales:orders", Map.of(), List.of(), List.of(),
                    limit, List.of())).containsKey("error");
            assertThat(tools.aggregate("sales:orders", List.of(), List.of("count"),
                    Map.of(), List.of(), limit)).containsKey("error");
        }
    }

    private static SourceProperties source(String url) {
        SourceProperties source = new SourceProperties();
        source.setUrl(url); source.setUsername("sa"); source.setPassword(""); source.setSchema("PUBLIC");
        return source;
    }

    private static void execute(String url, String... sql) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url); dataSource.setUser("sa"); dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String command : sql) statement.execute(command);
        }
    }
}
