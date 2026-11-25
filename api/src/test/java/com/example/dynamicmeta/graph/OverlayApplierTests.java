package com.example.dynamicmeta.graph;

import java.time.Instant;
import java.util.List;

import com.example.dynamicmeta.data.ColumnCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GR-006: {@link OverlayApplier} merges an {@link Overlay} into a built
 * {@link SchemaGraph} as a pure function — labels attach, decisions move
 * proposed relationships, manual relationships join, rejected ones are
 * hidden unless asked for, and every overlay id that matches nothing is
 * reported as an orphan without ever being deleted from the overlay.
 */
class OverlayApplierTests {

    private static final Instant GENERATED_AT = Instant.parse("2026-11-24T10:15:00Z");

    private static final String DECLARED_ID = "shop:orders.customer_id->shop:customers.id";
    private static final String CROSS_PROPOSAL_ID = "shop:orders.customer_id->crm:customers.id";
    private static final String WITHIN_PROPOSAL_ID = "shop:orders.legacy_id->shop:customers.id";

    @Test
    void labelsAttachToTheMatchingEntityAndColumn() {
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers", "Customers", "Every customer of the shop")
                .withLabel("shop:orders.legacy_id", "Legacy key", null);

        SchemaGraph applied = OverlayApplier.apply(graph(), overlay, false);

        GraphEntity customers = entity(applied, "shop:customers");
        assertThat(customers.label()).isEqualTo("Customers");
        assertThat(customers.description()).isEqualTo("Every customer of the shop");
        // The entity the overlay says nothing about keeps no label.
        GraphEntity orders = entity(applied, "shop:orders");
        assertThat(orders.label()).isNull();
        assertThat(orders.description()).isNull();
        GraphColumn legacy = column(applied, "shop:orders.legacy_id");
        assertThat(legacy.label()).isEqualTo("Legacy key");
        assertThat(legacy.description()).isNull();
        assertThat(column(applied, "shop:orders.customer_id").label()).isNull();
        assertThat(applied.orphans()).isEmpty();
    }

    @Test
    void decisionsConfirmAndRejectProposalsAndRejectedStayHiddenUntilIncluded() {
        Overlay overlay = Overlay.empty()
                .withDecision(CROSS_PROPOSAL_ID, "confirmed")
                .withDecision(WITHIN_PROPOSAL_ID, "rejected");

        SchemaGraph hidden = OverlayApplier.apply(graph(), overlay, false);

        assertThat(statusOf(hidden, CROSS_PROPOSAL_ID)).isEqualTo(GraphRelationship.CONFIRMED);
        assertThat(ids(hidden)).doesNotContain(WITHIN_PROPOSAL_ID);
        // The declared foreign key needs no review and is untouched.
        assertThat(statusOf(hidden, DECLARED_ID)).isEqualTo(GraphRelationship.DECLARED);
        assertThat(hidden.orphans()).isEmpty();

        SchemaGraph shown = OverlayApplier.apply(graph(), overlay, true);

        assertThat(statusOf(shown, CROSS_PROPOSAL_ID)).isEqualTo(GraphRelationship.CONFIRMED);
        assertThat(statusOf(shown, WITHIN_PROPOSAL_ID)).isEqualTo(GraphRelationship.REJECTED);
        assertThat(ids(shown)).containsExactlyInAnyOrder(
                DECLARED_ID, CROSS_PROPOSAL_ID, WITHIN_PROPOSAL_ID);
    }

    @Test
    void aDecisionOnADeclaredRelationshipIsIgnoredButNotAnOrphan() {
        Overlay overlay = Overlay.empty().withDecision(DECLARED_ID, "rejected");

        SchemaGraph applied = OverlayApplier.apply(graph(), overlay, false);

        assertThat(statusOf(applied, DECLARED_ID)).isEqualTo(GraphRelationship.DECLARED);
        assertThat(applied.orphans()).isEmpty();
    }

    @Test
    void manualRelationshipsAreAddedAndAMissingColumnBecomesAnOrphan() {
        Overlay overlay = Overlay.empty()
                .withManualRelationship("shop:orders.legacy_id", "crm:customers.id")
                .withManualRelationship("shop:orders.legacy_id", "shop:customers.name")
                .withManualRelationship("shop:orders.gone_id", "shop:customers.id");

        SchemaGraph applied = OverlayApplier.apply(graph(), overlay, false);

        GraphRelationship cross = relationship(applied,
                "shop:orders.legacy_id->crm:customers.id");
        assertThat(cross.origin()).isEqualTo(GraphRelationship.MANUAL);
        assertThat(cross.status()).isEqualTo(GraphRelationship.CONFIRMED);
        assertThat(cross.confidence()).isNull();
        assertThat(cross.scope()).isEqualTo(GraphRelationship.CROSS_SOURCE);
        assertThat(cross.evidence()).isEmpty();
        // The scope follows the sources of the two ends.
        assertThat(relationship(applied, "shop:orders.legacy_id->shop:customers.name").scope())
                .isEqualTo(GraphRelationship.WITHIN_SOURCE);
        assertThat(applied.relationships()).extracting(GraphRelationship::id)
                .containsExactly(
                        "shop:orders.customer_id->crm:customers.id",
                        "shop:orders.customer_id->shop:customers.id",
                        "shop:orders.legacy_id->crm:customers.id",
                        "shop:orders.legacy_id->shop:customers.id",
                        "shop:orders.legacy_id->shop:customers.name");
        assertThat(applied.orphans())
                .containsExactly("shop:orders.gone_id->shop:customers.id");
    }

    @Test
    void aLabelForADroppedTableBecomesAnOrphanAndIsNotDeletedFromTheOverlay() {
        Overlay overlay = Overlay.empty().withLabel("shop:archived", "Archived", null);

        SchemaGraph applied = OverlayApplier.apply(graph(), overlay, false);

        assertThat(applied.orphans()).containsExactly("shop:archived");
        // The overlay is untouched: when the table is discovered again the
        // label reattaches instead of having been lost.
        assertThat(overlay.labels()).containsKey("shop:archived");
        assertThat(overlay.labels().get("shop:archived").label()).isEqualTo("Archived");
    }

    @Test
    void orphansOfEveryKindAreSortedAndDistinct() {
        Overlay overlay = Overlay.empty()
                .withLabel("zzz:gone", "Gone", null)
                .withDecision("shop:orders.legacy_id->nowhere:gone.id", "confirmed")
                .withManualRelationship("shop:orders.gone_id", "nowhere:gone.id");

        SchemaGraph applied = OverlayApplier.apply(graph(), overlay, false);

        assertThat(applied.orphans()).containsExactly(
                "shop:orders.gone_id->nowhere:gone.id",
                "shop:orders.legacy_id->nowhere:gone.id",
                "zzz:gone");
    }

    @Test
    void anEmptyOverlayChangesNothing() {
        SchemaGraph applied = OverlayApplier.apply(graph(), Overlay.empty(), false);

        assertThat(applied.specVersion()).isEqualTo(SchemaGraph.SPEC_VERSION);
        assertThat(applied.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(applied.sources()).isEqualTo(graph().sources());
        assertThat(applied.entities()).isEqualTo(graph().entities());
        assertThat(ids(applied)).containsExactly(CROSS_PROPOSAL_ID, DECLARED_ID,
                WITHIN_PROPOSAL_ID);
        assertThat(applied.orphans()).isEmpty();
    }

    @Test
    void applyingIsPureAndDeterministic() {
        SchemaGraph original = graph();
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers", "Customers", null)
                .withDecision(CROSS_PROPOSAL_ID, "confirmed")
                .withManualRelationship("shop:orders.legacy_id", "crm:customers.id");

        SchemaGraph first = OverlayApplier.apply(original, overlay, false);
        SchemaGraph second = OverlayApplier.apply(original, overlay, false);

        assertThat(first).isEqualTo(second);
        // The inputs are unchanged: the document keeps its bare entities and
        // its proposed relationship, and the overlay keeps every edit.
        assertThat(entity(original, "shop:customers").label()).isNull();
        assertThat(original.relationships()).hasSize(3);
        assertThat(statusOf(original, CROSS_PROPOSAL_ID)).isEqualTo(GraphRelationship.PROPOSED);
        assertThat(overlay.labels()).hasSize(1);
        assertThat(overlay.relationshipDecisions()).hasSize(1);
        assertThat(overlay.manualRelationships()).hasSize(1);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Two sources — shop with a declared foreign key, a cross-source
     * proposal and a within-source proposal, crm with the proposed target —
     * as {@code SchemaGraphBuilder} would hand them over: entities and
     * relationships sorted by id, no labels and no orphans.
     */
    private static SchemaGraph graph() {
        GraphEntity shopCustomers = new GraphEntity("shop:customers", "shop", "customers",
                "table", null, null, List.of("id"),
                List.of(column("shop", "customers", "id", "bigint", ColumnCategory.NUMBER, false),
                        column("shop", "customers", "name", "varchar(80)",
                                ColumnCategory.TEXT, true)),
                List.of());
        GraphEntity shopOrders = new GraphEntity("shop:orders", "shop", "orders",
                "table", null, null, List.of("id"),
                List.of(column("shop", "orders", "customer_id", "bigint",
                                ColumnCategory.NUMBER, true),
                        column("shop", "orders", "legacy_id", "bigint",
                                ColumnCategory.NUMBER, true)),
                List.of());
        GraphEntity crmCustomers = new GraphEntity("crm:customers", "crm", "customers",
                "table", null, null, List.of("id"),
                List.of(column("crm", "customers", "id", "bigint", ColumnCategory.NUMBER, false)),
                List.of());

        GraphRelationship declared = new GraphRelationship(DECLARED_ID,
                "shop:orders.customer_id", "shop:customers.id",
                GraphRelationship.DECLARED, GraphRelationship.WITHIN_SOURCE,
                GraphRelationship.DECLARED, null, List.of());
        GraphRelationship crossProposal = new GraphRelationship(CROSS_PROPOSAL_ID,
                "shop:orders.customer_id", "crm:customers.id",
                GraphRelationship.INFERRED, GraphRelationship.CROSS_SOURCE,
                GraphRelationship.PROPOSED, 0.8, List.of("name-match"));
        GraphRelationship withinProposal = new GraphRelationship(WITHIN_PROPOSAL_ID,
                "shop:orders.legacy_id", "shop:customers.id",
                GraphRelationship.INFERRED, GraphRelationship.WITHIN_SOURCE,
                GraphRelationship.PROPOSED, 0.9, List.of("view-column-name"));

        return new SchemaGraph(GENERATED_AT,
                List.of(new GraphSource("shop", "h2", null, "PUBLIC", GraphSource.OK, null),
                        new GraphSource("crm", "h2", null, "PUBLIC", GraphSource.OK, null)),
                List.of(crmCustomers, shopCustomers, shopOrders),
                List.of(declared, crossProposal, withinProposal),
                List.of());
    }

    private static GraphColumn column(String source, String entity, String name, String type,
                                       ColumnCategory category, boolean nullable) {
        return new GraphColumn(GraphIds.columnId(source, entity, name), name, type, category,
                nullable, null, null);
    }

    private static GraphEntity entity(SchemaGraph graph, String id) {
        return graph.entities().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst().orElseThrow();
    }

    private static GraphColumn column(SchemaGraph graph, String id) {
        return graph.entities().stream()
                .flatMap(candidate -> candidate.columns().stream())
                .filter(candidate -> candidate.id().equals(id))
                .findFirst().orElseThrow();
    }

    private static GraphRelationship relationship(SchemaGraph graph, String id) {
        return graph.relationships().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst().orElseThrow();
    }

    private static String statusOf(SchemaGraph graph, String id) {
        return relationship(graph, id).status();
    }

    private static List<String> ids(SchemaGraph graph) {
        return graph.relationships().stream().map(GraphRelationship::id).toList();
    }
}
