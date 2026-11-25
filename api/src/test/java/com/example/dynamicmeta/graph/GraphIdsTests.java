package com.example.dynamicmeta.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GR-002: the graph id builders lower-case every part and the parse helpers
 * round-trip them back into their parts.
 */
class GraphIdsTests {

    @Test
    void entityIdIsLowerCasedAndRoundTrips() {
        String id = GraphIds.entityId("Orders", "Customers");

        assertThat(id).isEqualTo("orders:customers");

        GraphIds.EntityId parts = GraphIds.parseEntityId(id);
        assertThat(parts.source()).isEqualTo("orders");
        assertThat(parts.entity()).isEqualTo("customers");
    }

    @Test
    void entityIdTrimsAndLowerCasesMixedCaseParts() {
        assertThat(GraphIds.entityId("  Orders ", "ORDER_ITEMS")).isEqualTo("orders:order_items");

        GraphIds.EntityId parts = GraphIds.parseEntityId("ORDERS:Order_Items");
        assertThat(parts.source()).isEqualTo("orders");
        assertThat(parts.entity()).isEqualTo("order_items");
    }

    @Test
    void columnIdIsLowerCasedAndRoundTrips() {
        String id = GraphIds.columnId("Orders", "OrderItems", "ProductId");

        assertThat(id).isEqualTo("orders:orderitems.productid");

        GraphIds.ColumnId parts = GraphIds.parseColumnId(id);
        assertThat(parts.source()).isEqualTo("orders");
        assertThat(parts.entity()).isEqualTo("orderitems");
        assertThat(parts.column()).isEqualTo("productid");
    }

    @Test
    void relationshipIdIsLowerCasedAndRoundTrips() {
        String from = GraphIds.columnId("Orders", "OrderItems", "ProductId");
        String to = GraphIds.columnId("Catalog", "Products", "Id");

        String id = GraphIds.relationshipId(from, to);

        assertThat(id).isEqualTo("orders:orderitems.productid->catalog:products.id");

        GraphIds.RelationshipId parts = GraphIds.parseRelationshipId(id);
        assertThat(parts.from()).isEqualTo(from);
        assertThat(parts.to()).isEqualTo(to);
    }

    @Test
    void relationshipIdIsDeterministicForTheSamePair() {
        String from = GraphIds.columnId("Orders", "OrderItems", "ProductId");
        String to = GraphIds.columnId("Catalog", "Products", "Id");

        assertThat(GraphIds.relationshipId(from, to))
                .isEqualTo(GraphIds.relationshipId(from, to))
                .isEqualTo(GraphIds.relationshipId(
                        " ORDERS:OrderItems.ProductId ", "catalog:Products.ID"));
    }

    @Test
    void relationshipIdKeepsThePairOrder() {
        String from = GraphIds.columnId("Orders", "OrderItems", "ProductId");
        String to = GraphIds.columnId("Catalog", "Products", "Id");

        assertThat(GraphIds.relationshipId(from, to))
                .isNotEqualTo(GraphIds.relationshipId(to, from));
    }

    @Test
    void blankPartsAreRejected() {
        assertThatThrownBy(() -> GraphIds.entityId(" ", "customers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> GraphIds.columnId("orders", "items", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column");
        assertThatThrownBy(() -> GraphIds.relationshipId(null, "catalog:products.id"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void separatorInAPartIsRejected() {
        assertThatThrownBy(() -> GraphIds.entityId("or:ders", "customers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> GraphIds.columnId("orders", "order.items", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entity");
        assertThatThrownBy(() -> GraphIds.relationshipId(
                "orders:items.id->catalog:products.id", "catalog:products.id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromColumnId");
    }

    @Test
    void unparseableIdsAreRejected() {
        assertThatThrownBy(() -> GraphIds.parseEntityId("customers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source:entity");
        assertThatThrownBy(() -> GraphIds.parseColumnId("orders:orderitems"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source:entity.column");
        assertThatThrownBy(() -> GraphIds.parseRelationshipId("orders:orderitems.productid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromColumnId->toColumnId");
        assertThatThrownBy(() -> GraphIds.parseEntityId(":customers"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
