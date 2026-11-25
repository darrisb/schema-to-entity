package com.example.dynamicmeta.graph;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.connection.ViewDependencyReader;
import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.DataSourceDefinitions;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.ForeignKeyModel;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.data.SourceSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.mockito.MockedConstruction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * GR-003: the builder turns every configured source's cached schema into one
 * {@link SchemaGraph} with source-prefixed ids and within-source
 * relationships, and an unavailable source never breaks the document. The
 * data API is read-only; these tests only read metadata.
 */
class SchemaGraphBuilderTests {

    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    @Test
    void buildsEntitiesFromEveryConfiguredSourceWithSourcePrefixedIds() throws Exception {
        seed("ste-graph-alpha",
                "create table customers (id bigint primary key, name varchar(80) not null)");
        seed("ste-graph-beta",
                "create table customers (id bigint primary key, email varchar(120))");
        // Configured out of name order on purpose: the document is sorted by id.
        Map<String, SourceProperties> sources = new LinkedHashMap<>();
        sources.put("beta", h2Source("ste-graph-beta"));
        sources.put("alpha", h2Source("ste-graph-alpha"));
        SourceRegistry registry = registry(sources);

        SchemaGraph graph = builder(registry).build(Set.of());

        assertThat(graph.specVersion()).isEqualTo(SchemaGraph.SPEC_VERSION);
        assertThat(graph.generatedAt()).isNotNull();
        assertThat(graph.sources()).extracting(GraphSource::id)
                .containsExactly("alpha", "beta");
        assertThat(graph.sources()).allSatisfy(source -> {
            assertThat(source.status()).isEqualTo(GraphSource.OK);
            assertThat(source.databaseType()).isEqualTo("h2");
            assertThat(source.error()).isNull();
        });

        // The same table name in both sources gives two distinct entities.
        assertThat(graph.entities()).extracting(GraphEntity::id)
                .containsExactly("alpha:customers", "beta:customers");

        GraphEntity alpha = graph.entities().getFirst();
        assertThat(alpha.source()).isEqualTo("alpha");
        // The id is lower-cased; the name is kept as the driver reported it.
        assertThat(alpha.name()).isEqualTo("CUSTOMERS");
        assertThat(alpha.kind()).isEqualTo("table");
        assertThat(alpha.label()).isNull();
        assertThat(alpha.description()).isNull();
        assertThat(alpha.keyColumns()).containsExactly("ID");
        assertThat(alpha.baseTables()).isEmpty();
        assertThat(alpha.columns()).extracting(GraphColumn::id)
                .containsExactly("alpha:customers.id", "alpha:customers.name");
        assertThat(alpha.columns()).extracting(GraphColumn::name)
                .containsExactly("ID", "NAME");
        GraphColumn id = alpha.columns().getFirst();
        assertThat(id.type()).isEqualTo("bigint");
        assertThat(id.category()).isEqualTo(ColumnCategory.NUMBER);
        assertThat(id.nullable()).isFalse();
        assertThat(id.label()).isNull();
        assertThat(id.description()).isNull();

        assertThat(graph.relationships()).isEmpty();
        assertThat(graph.orphans()).isEmpty();

        registry.destroy();
    }

    @Test
    void declaredForeignKeyBecomesADeclaredWithinSourceRelationship() throws Exception {
        seed("ste-graph-declared",
                "create table customers (id bigint primary key)",
                "create table orders (id bigint primary key, customer_id bigint not null, "
                        + "foreign key (customer_id) references customers (id))");
        SourceRegistry registry = registry(Map.of("shop", h2Source("ste-graph-declared")));

        SchemaGraph graph = builder(registry).build(Set.of());

        assertThat(graph.relationships()).hasSize(1);
        GraphRelationship relationship = graph.relationships().getFirst();
        assertThat(relationship.id()).isEqualTo("shop:orders.customer_id->shop:customers.id");
        assertThat(relationship.from()).isEqualTo("shop:orders.customer_id");
        assertThat(relationship.to()).isEqualTo("shop:customers.id");
        assertThat(relationship.origin()).isEqualTo(GraphRelationship.DECLARED);
        assertThat(relationship.scope()).isEqualTo(GraphRelationship.WITHIN_SOURCE);
        assertThat(relationship.status()).isEqualTo(GraphRelationship.DECLARED);
        assertThat(relationship.confidence()).isNull();
        assertThat(relationship.evidence()).isEmpty();

        registry.destroy();
    }

    @Test
    void inferredForeignKeyFromAViewBecomesAProposedRelationship() throws Exception {
        seed("ste-graph-inferred",
                "create table customers (id bigint primary key)",
                "create table orders (id bigint primary key, customer_id bigint not null, "
                        + "foreign key (customer_id) references customers (id))",
                "create view order_overview as select id, customer_id from orders");
        SourceRegistry registry = registry(Map.of("shop", h2Source("ste-graph-inferred")));

        // H2 has no view dependency catalog, so the reader is mocked like in
        // SchemaCacheTests to supply the view's base tables.
        try (MockedConstruction<ViewDependencyReader> mocked = mockConstruction(
                ViewDependencyReader.class,
                (reader, context) -> when(reader.read(any(), any(), any(), any()))
                        .thenReturn(Map.of("ORDER_OVERVIEW", List.of("orders"))))) {
            SchemaGraph graph = builder(registry).build(Set.of());

            assertThat(graph.relationships()).hasSize(2);
            GraphRelationship inferred = graph.relationships().stream()
                    .filter(relationship -> relationship.id()
                            .equals("shop:order_overview.customer_id->shop:customers.id"))
                    .findFirst().orElseThrow();
            assertThat(inferred.origin()).isEqualTo(GraphRelationship.INFERRED);
            assertThat(inferred.scope()).isEqualTo(GraphRelationship.WITHIN_SOURCE);
            assertThat(inferred.status()).isEqualTo(GraphRelationship.PROPOSED);
            assertThat(inferred.confidence()).isEqualTo(0.9);
            assertThat(inferred.evidence()).containsExactly("view-column-name");

            // The view's base tables are recorded as entity ids of the same source.
            GraphEntity view = graph.entities().stream()
                    .filter(entity -> entity.id().equals("shop:order_overview"))
                    .findFirst().orElseThrow();
            assertThat(view.kind()).isEqualTo("view");
            assertThat(view.baseTables()).containsExactly("shop:orders");
        }

        registry.destroy();
    }

    @Test
    void foreignKeyWhoseTargetEntityIsNotInTheDocumentIsSkipped() throws Exception {
        seed("ste-graph-skipped-target",
                "create table customers (id bigint primary key)",
                "create table orders (id bigint primary key, customer_id bigint not null, "
                        + "foreign key (customer_id) references customers (id))");
        // The allowlist leaves customers out of the document, so the foreign
        // key pointing at it is skipped rather than dangling.
        SourceProperties source = h2Source("ste-graph-skipped-target");
        source.setTables(List.of("orders"));
        SourceRegistry registry = registry(Map.of("solo", source));

        SchemaGraph graph = builder(registry).build(Set.of());

        assertThat(graph.entities()).extracting(GraphEntity::id).containsExactly("solo:orders");
        assertThat(graph.relationships()).isEmpty();

        registry.destroy();
    }

    @Test
    void foreignKeyWhoseTargetColumnIsNotInTheDocumentIsSkipped() {
        SourceSchema schema = new SourceSchema("h2", null, "PUBLIC", Map.of(
                "orders", new EntityModel("orders", "table",
                        List.of(new ColumnModel("id", "bigint", ColumnCategory.NUMBER, false),
                                new ColumnModel("customer_id", "bigint",
                                        ColumnCategory.NUMBER, true)),
                        List.of("id"),
                        List.of(new ForeignKeyModel(
                                null, "customer_id", "customers", "gone", "declared")),
                        List.of()),
                "customers", new EntityModel("customers", "table",
                        List.of(new ColumnModel("id", "bigint", ColumnCategory.NUMBER, false)),
                        List.of("id"))));
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("solo")).thenReturn(schema);
        SourceRegistry registry = registry(Map.of("solo", h2Source("ste-graph-mock-column")));

        SchemaGraph graph = new SchemaGraphBuilder(registry, cache).build(Set.of());

        assertThat(graph.entities()).extracting(GraphEntity::id)
                .containsExactly("solo:customers", "solo:orders");
        assertThat(graph.relationships()).isEmpty();
    }

    @Test
    void aSourceThatFailsDiscoveryIsUnavailableAndOthersAreStillBuilt() throws Exception {
        seed("ste-graph-alive", "create table customers (id bigint primary key)");
        // A database that was never created: every connection attempt fails,
        // and the driver message must not reach the document.
        SourceProperties broken = h2Source("ste-graph-broken");
        broken.setUrl("jdbc:h2:mem:ste-graph-broken;DB_CLOSE_DELAY=-1;IFEXISTS=TRUE");
        Map<String, SourceProperties> sources = new LinkedHashMap<>();
        sources.put("broken", broken);
        sources.put("alive", h2Source("ste-graph-alive"));
        SourceRegistry registry = registry(sources);

        SchemaGraph graph = builder(registry).build(Set.of());

        assertThat(graph.sources()).extracting(GraphSource::id)
                .containsExactly("alive", "broken");
        GraphSource unavailable = graph.sources().get(1);
        assertThat(unavailable.status()).isEqualTo(GraphSource.UNAVAILABLE);
        // Generic reason only: the driver message names the missing database.
        assertThat(unavailable.error()).isEqualTo("Schema discovery failed");
        assertThat(unavailable.catalog()).isNull();
        assertThat(unavailable.schema()).isNull();

        // The available source is unaffected by its neighbour's failure.
        GraphSource ok = graph.sources().getFirst();
        assertThat(ok.status()).isEqualTo(GraphSource.OK);
        assertThat(ok.error()).isNull();
        assertThat(graph.entities()).extracting(GraphEntity::id)
                .containsExactly("alive:customers");

        registry.destroy();
    }

    @Test
    void explicitSourceNamesRestrictTheDocument() throws Exception {
        seed("ste-graph-one", "create table customers (id bigint primary key)");
        seed("ste-graph-two", "create table orders (id bigint primary key)");
        Map<String, SourceProperties> sources = new LinkedHashMap<>();
        sources.put("one", h2Source("ste-graph-one"));
        sources.put("two", h2Source("ste-graph-two"));
        SourceRegistry registry = registry(sources);

        SchemaGraph graph = builder(registry).build(Set.of("two"));

        assertThat(graph.sources()).extracting(GraphSource::id).containsExactly("two");
        assertThat(graph.entities()).extracting(GraphEntity::id).containsExactly("two:orders");

        registry.destroy();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static SourceProperties h2Source(String database) {
        SourceProperties source = new SourceProperties();
        source.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        return source;
    }

    private static SourceRegistry registry(Map<String, SourceProperties> sources) {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(sources);
        return new SourceRegistry(
                DataSourceDefinitions.from(properties, PARSER, POLICY),
                FACTORY, PARSER, Duration.ofMillis(500));
    }

    private static SchemaGraphBuilder builder(SourceRegistry registry) {
        return new SchemaGraphBuilder(registry,
                new SchemaCache(registry, discovery(), new SchemaToEntityProperties()));
    }

    private static SchemaDiscoveryService discovery() {
        return new SchemaDiscoveryService(null, new SchemaToEntityProperties(), POLICY, FACTORY);
    }

    private static void seed(String database, String... statements) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
