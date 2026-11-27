package com.example.dynamicmeta.data;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.graph.GraphRelationship;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraph;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationshipExpanderTests {

    @TempDir
    Path tempDir;

    @Test
    void resolvesSameSourceDeclaredForeignKey() {
        RelationshipExpander.Expansion expansion = expander(relationship(
                "orders:orders.customer_id", "orders:customers.id",
                GraphRelationship.DECLARED)).resolve("orders", "orders", "customer_id");

        assertThat(expansion.targetSource()).isEqualTo("orders");
        assertThat(expansion.targetEntity()).isEqualTo("customers");
        assertThat(expansion.targetColumn()).isEqualTo("id");
    }

    @Test
    void resolvesCrossSourceConfirmedRelationship() {
        RelationshipExpander.Expansion expansion = expander(relationship(
                "orders:orders.customer_id", "crm:customers.id",
                GraphRelationship.CONFIRMED)).resolve("orders", "orders", "customer_id");

        assertThat(expansion.targetSource()).isEqualTo("crm");
        assertThat(expansion.targetEntity()).isEqualTo("customers");
    }

    @Test
    void refusesProposedRelationship() {
        RelationshipExpander expander = expander(relationship(
                "orders:orders.customer_id", "crm:customers.id",
                GraphRelationship.PROPOSED));

        assertThatThrownBy(() -> expander.resolve("orders", "orders", "customer_id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No usable relationship for customer_id");
    }

    @Test
    void refusesMoreThanThreeExpandColumns() {
        assertThatThrownBy(() -> DataQueryService.validateExpansions(
                List.of("customer_id,product_id,owner_id,status_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At most 3 expand columns are allowed");
    }

    @Test
    void graphDisabledExplainsRequirementBeforeReadingSchema() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setGraphEnabled(false);
        DataQueryService service = new DataQueryService(mock(SourceRegistry.class),
                mock(SchemaCache.class), mock(QueryParameterParser.class), properties);

        assertThatThrownBy(() -> service.query("orders", "orders", java.util.Map.of(),
                false, List.of("customer_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expand requires graph-enabled=true");
    }

    @Test
    void sameSourceExpansionWorksWithOneConnectionPool() throws Exception {
        String url = "jdbc:h2:mem:ste_expand_single_connection;DB_CLOSE_DELAY=-1";
        seed(url);
        SourceProperties source = new SourceProperties();
        source.setUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        source.setMaxPoolSize(1);

        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));
        properties.setGraphEnabled(true);
        ConnectionStringParser parser = new ConnectionStringParser();
        ConnectionPolicy policy = new ConnectionPolicy(List.of(), true);
        JdbcConnectionFactory factory = new JdbcConnectionFactory(
                Duration.ofSeconds(10), Duration.ofSeconds(60));
        SourceRegistry registry = new SourceRegistry(
                DataSourceDefinitions.from(properties, parser, policy),
                factory, parser, Duration.ofMillis(250));
        try {
            SchemaCache cache = new SchemaCache(registry,
                    new SchemaDiscoveryService(null, properties, policy, factory), properties);
            RelationshipExpander expander = mock(RelationshipExpander.class);
            when(expander.resolve("orders", "ORDERS", "CUSTOMER_ID"))
                    .thenReturn(new RelationshipExpander.Expansion(
                            "CUSTOMER_ID", "orders", "CUSTOMERS", "ID"));
            DataQueryService service = new DataQueryService(registry, cache,
                    new QueryParameterParser(properties), properties, () -> expander);

            DataQueryService.DataPage page = service.query("orders", "orders", Map.of(), false,
                    List.of("customer_id"));

            assertThat(page.items()).singleElement().satisfies(row -> {
                Map<?, ?> expanded = (Map<?, ?>) row.get("_expanded");
                assertThat(expanded.get("customer_id"))
                        .isEqualTo(Map.of("ID", 10L, "NAME", "Alice"));
            });
        } finally {
            registry.destroy();
        }
    }

    private static void seed(String url) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint primary key, name varchar(40))");
            statement.execute("create table orders (id bigint primary key, customer_id bigint, "
                    + "foreign key (customer_id) references customers (id))");
            statement.execute("insert into customers values (10, 'Alice')");
            statement.execute("insert into orders values (1, 10)");
        }
    }

    private RelationshipExpander expander(GraphRelationship relationship) {
        SchemaGraphBuilder builder = mock(SchemaGraphBuilder.class);
        when(builder.sourceNames()).thenReturn(Set.of("orders", "crm"));
        when(builder.build(Set.of("orders", "crm"))).thenReturn(new SchemaGraph(
                Instant.EPOCH, List.of(), List.of(), List.of(relationship), List.of()));
        return new RelationshipExpander(builder,
                new OverlayStore(tempDir.resolve("overlay.json")));
    }

    private static GraphRelationship relationship(String from, String to, String status) {
        String scope = from.substring(0, from.indexOf(':'))
                .equals(to.substring(0, to.indexOf(':')))
                ? GraphRelationship.WITHIN_SOURCE : GraphRelationship.CROSS_SOURCE;
        String origin = GraphRelationship.DECLARED.equals(status)
                ? GraphRelationship.DECLARED : GraphRelationship.INFERRED;
        return new GraphRelationship(from + "->" + to, from, to, origin, scope,
                status, null, List.of());
    }
}
