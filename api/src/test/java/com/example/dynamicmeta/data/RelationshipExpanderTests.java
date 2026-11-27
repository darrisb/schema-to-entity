package com.example.dynamicmeta.data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.example.dynamicmeta.graph.GraphRelationship;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraph;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;
import com.example.dynamicmeta.SchemaToEntityProperties;
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
