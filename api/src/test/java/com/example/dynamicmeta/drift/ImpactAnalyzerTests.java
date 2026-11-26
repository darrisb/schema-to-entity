package com.example.dynamicmeta.drift;

import java.time.Instant;
import java.util.List;

import com.example.dynamicmeta.drift.SchemaChange.Severity;
import com.example.dynamicmeta.drift.SchemaChange.Type;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;
import com.example.dynamicmeta.graph.GraphIds;
import com.example.dynamicmeta.graph.Overlay;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-005: {@link ImpactAnalyzer} says what each {@link SchemaChange} affects —
 * the overlay labels, relationship decisions and manual relationships and the
 * baseline's declared foreign keys that refer to the changed entity or column.
 * These tests build the baseline snapshot and the overlay in memory: nothing here
 * reads or writes a database, and, as the last group of tests shows, the analysis
 * leaves the overlay exactly as it found it.
 */
class ImpactAnalyzerTests {

    private static final String SOURCE = "shop";
    private static final Instant TAKEN_AT = Instant.parse("2026-11-25T10:00:00Z");

    private static final String ORDERS_KEY = "shop:orders.customer_id->shop:customers.id";
    private static final String SHIPPED_KEY = "shop:orders.shipped_to->shop:customers.id";

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    void droppingALabelledColumnListsItsLabel() {
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers.email", "Work address", "Where mail bounces")
                .withLabel("shop:orders.note", "Note", null);

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedColumn("customers", "email"));

        assertThat(impact.labels()).containsExactly("shop:customers.email");
        assertThat(impact.change().type()).isEqualTo(Type.COLUMN_REMOVED);
    }

    @Test
    void aColumnChangeOwnsThatColumnAndNothingElse() {
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers.email", "Work address", null)
                // The table is still there, so its own label is not affected, and
                // these two columns merely look similar.
                .withLabel("shop:customers", "Customers", null)
                .withLabel("shop:customers.email_hash", "Hash", null)
                .withLabel("shop:customers_extra.email", "Other table", null);

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedColumn("customers", "email"));

        assertThat(impact.labels()).containsExactly("shop:customers.email");
    }

    @Test
    void droppingAnEntityListsTheLabelsOfItsColumnsToo() {
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers", "Customers", null)
                .withLabel("shop:customers.email", "Work address", null)
                .withLabel("shop:customers.id", "Primary key", null)
                // Nothing below the entity, despite the shared prefix.
                .withLabel("shop:customers_archive.id", "Archived", null)
                .withLabel("shop:other.customers", "Another table", null);

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedEntity("customers"));

        assertThat(impact.labels()).containsExactly("shop:customers", "shop:customers.email",
                "shop:customers.id");
    }

    @Test
    void anotherSourcesTableOfTheSameNameIsUnaffected() {
        Overlay overlay = Overlay.empty()
                .withLabel("billing:customers.email", "Billing address", null)
                .withLabel("shop:customers.email", "Work address", null);

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedColumn("customers", "email"));

        // The ids a change owns are spelled with the source of its baseline.
        assertThat(impact.labels()).containsExactly("shop:customers.email");
    }

    // ------------------------------------------------------------------
    // Decisions and manual relationships
    // ------------------------------------------------------------------

    @Test
    void droppingAColumnUsedByAConfirmedCrossSourceRelationshipListsTheDecision() {
        String crossSource = GraphIds.relationshipId("shop:customers.email",
                "billing:invoices.customer_email");
        Overlay overlay = Overlay.empty()
                .withDecision(crossSource, "confirmed")
                .withDecision(GraphIds.relationshipId("shop:orders.note",
                        "billing:invoices.total"), "rejected");

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedColumn("customers", "email"));

        assertThat(impact.decisions()).containsExactly(crossSource);
    }

    @Test
    void aRelationshipIsMatchedOnEitherSideOfTheArrow() {
        String emailPointsAway = GraphIds.relationshipId("shop:customers.email",
                "billing:invoices.customer_email");
        String emailPointsHere = GraphIds.relationshipId("billing:invoices.customer_email",
                "shop:customers.email");
        Overlay overlay = Overlay.empty()
                .withDecision(emailPointsAway, "confirmed")
                .withDecision(emailPointsHere, "confirmed")
                .withManualRelationship("shop:orders.note", "shop:customers.email");

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedColumn("customers", "email"));

        // Both arrows are found, and the ids come back sorted, whatever order the
        // overlay happened to hold them in.
        assertThat(impact.decisions()).containsExactly(emailPointsHere, emailPointsAway);
        // A person asserted the pair by hand: it is reported by its relationship id.
        assertThat(impact.manualRelationships())
                .containsExactly("shop:orders.note->shop:customers.email");
    }

    @Test
    void droppingAnEntityListsRelationshipsWhicheverEndItsColumnIsOn() {
        Overlay overlay = Overlay.empty()
                .withDecision(ORDERS_KEY, "confirmed")
                .withDecision("billing:invoices.customer_id->shop:customers.id", "rejected")
                .withDecision("billing:invoices.total->crm:orders.amount", "confirmed")
                .withManualRelationship("shop:customers.email", "billing:invoices.customer_email");

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay,
                removedEntity("customers"));

        assertThat(impact.decisions())
                .containsExactly("billing:invoices.customer_id->shop:customers.id", ORDERS_KEY);
        assertThat(impact.manualRelationships())
                .containsExactly("shop:customers.email->billing:invoices.customer_email");
    }

    @Test
    void aRenameCandidateMatchesTheColumnItNamesNotItsPartner() {
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers.email", "Old name", null)
                .withLabel("shop:customers.email_hash", "New name", null);
        SchemaChange candidate = new SchemaChange(Type.COLUMN_RENAME_CANDIDATE, Severity.REVIEW,
                "customers", "email", "varchar(120) nullable", "varchar(64) nullable",
                "email_hash");

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline(), overlay, candidate);

        // The addition of email_hash is its own change and brings its own ids.
        assertThat(impact.labels()).containsExactly("shop:customers.email");
    }

    // ------------------------------------------------------------------
    // Baseline foreign keys
    // ------------------------------------------------------------------

    @Test
    void theBaselineKeysUsingTheColumnAreListedAsRelationshipIds() {
        SchemaSnapshot withKeys = baseline(ordersWithKeys());

        assertThat(ImpactAnalyzer.impactOf(withKeys, Overlay.empty(),
                removedColumn("orders", "customer_id")).foreignKeys())
                        .containsExactly(ORDERS_KEY);
        // The referenced end counts as well: dropping customers.id breaks both keys.
        assertThat(ImpactAnalyzer.impactOf(withKeys, Overlay.empty(),
                removedColumn("customers", "id")).foreignKeys())
                        .containsExactly(ORDERS_KEY, SHIPPED_KEY);
        assertThat(ImpactAnalyzer.impactOf(withKeys, Overlay.empty(),
                removedColumn("customers", "email")).foreignKeys()).isEmpty();
    }

    @Test
    void droppingAnEntityListsEveryBaselineKeyThatTouchesIt() {
        SchemaSnapshot withKeys = baseline(ordersWithKeys(), entity("audit_log"));

        assertThat(ImpactAnalyzer.impactOf(withKeys, Overlay.empty(),
                removedEntity("customers")).foreignKeys())
                        .containsExactly(ORDERS_KEY, SHIPPED_KEY);
        assertThat(ImpactAnalyzer.impactOf(withKeys, Overlay.empty(),
                removedEntity("audit_log")).foreignKeys()).isEmpty();
    }

    @Test
    void keysAreNamedTheWayTheGraphNamesThemHoweverTheDriverSpelledThem() {
        // A driver reports upper-case names; the ids a reviewer holds are lower-case.
        SchemaSnapshot shouted = baseline(new SnapshotEntity("ORDERS", "table", List.of(),
                List.of(), List.of(new SnapshotForeignKey("CUSTOMER_ID", "CUSTOMERS", "ID"))));
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers.id", "Primary key", null)
                .withDecision(ORDERS_KEY, "confirmed");

        ChangeImpact impact = ImpactAnalyzer.impactOf(shouted, overlay,
                removedColumn("CUSTOMERS", "ID"));

        assertThat(impact.labels()).containsExactly("shop:customers.id");
        assertThat(impact.decisions()).containsExactly(ORDERS_KEY);
        assertThat(impact.foreignKeys()).containsExactly(ORDERS_KEY);
    }

    @Test
    void aKeyThatCannotFormAnIdIsSkippedRatherThanFailingTheCheck() {
        SchemaSnapshot odd = baseline(entity("orders", new SnapshotForeignKey("customer_id",
                "crm:customers", "id")));

        ChangeImpact impact = ImpactAnalyzer.impactOf(odd, Overlay.empty(),
                removedEntity("orders"));

        // The graph could not name that relationship either, so there is no id to show.
        assertThat(impact.foreignKeys()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Reporting shape
    // ------------------------------------------------------------------

    @Test
    void analyzeGivesOneImpactPerChangeInTheSameOrder() {
        List<SchemaChange> changes = List.of(
                removedColumn("customers", "email"),
                removedEntity("orders"),
                new SchemaChange(Type.ENTITY_ADDED, Severity.ADDITIVE, "audit_log", null,
                        null, "table", null));
        Overlay overlay = Overlay.empty().withLabel("shop:orders", "Purchases", null);

        List<ChangeImpact> impacts = ImpactAnalyzer.analyze(baseline(), overlay, changes);

        assertThat(impacts).hasSize(3);
        assertThat(impacts).extracting(ChangeImpact::change).containsExactlyElementsOf(changes);
        // The added table has nothing referring to it: an entry with empty lists,
        // not a missing one.
        assertThat(impacts.get(0).isEmpty()).isTrue();
        assertThat(impacts.get(1).labels()).containsExactly("shop:orders");
        assertThat(impacts.get(2).isEmpty()).isTrue();
        assertThat(ImpactAnalyzer.analyze(baseline(), overlay, List.of())).isEmpty();
    }

    @Test
    void anImpactListsEachIdOnceAndInOrder() {
        SchemaChange change = removedEntity("customers");

        ChangeImpact impact = new ChangeImpact(change,
                List.of("shop:customers.id", "shop:customers", "shop:customers.id"),
                List.of(ORDERS_KEY, SHIPPED_KEY), List.of(), List.of());

        assertThat(impact.labels()).containsExactly("shop:customers", "shop:customers.id");
        assertThat(impact.decisions()).containsExactly(ORDERS_KEY, SHIPPED_KEY);
        assertThat(impact.manualRelationships()).isEmpty();
        assertThat(impact.foreignKeys()).isEmpty();
        assertThat(ChangeImpact.none(change).isEmpty()).isTrue();
    }

    @Test
    void analyzingLeavesTheOverlayUntouched() {
        Overlay overlay = Overlay.empty()
                .withLabel("shop:customers.email", "Work address", null)
                .withDecision(ORDERS_KEY, "confirmed")
                .withManualRelationship("shop:orders.customer_id", "shop:customers.email");
        SchemaSnapshot baseline = baseline(ordersWithKeys());

        ChangeImpact impact = ImpactAnalyzer.impactOf(baseline, overlay,
                removedColumn("customers", "email"));

        assertThat(overlay.labels()).hasSize(1);
        assertThat(overlay.relationshipDecisions()).hasSize(1);
        assertThat(overlay.manualRelationships()).hasSize(1);
        assertThat(impact.isEmpty()).isFalse();
    }

    @Test
    void theAnalysisRefusesToWorkWithoutABaselineOrAnOverlay() {
        SchemaChange change = removedColumn("customers", "email");

        assertThatThrownBy(() -> ImpactAnalyzer.impactOf(null, Overlay.empty(), change))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("baseline");
        assertThatThrownBy(() -> ImpactAnalyzer.analyze(baseline(), null, List.of(change)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("overlay");
        assertThatThrownBy(() -> ImpactAnalyzer.impactOf(baseline(), Overlay.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("change");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A baseline of the two tables the ids above name; impact reads names, not columns. */
    private static SchemaSnapshot baseline() {
        return baseline(entity("customers"), entity("orders"));
    }

    private static SchemaSnapshot baseline(SnapshotEntity... entities) {
        return new SchemaSnapshot(SOURCE, "0".repeat(64), TAKEN_AT, "h2", List.of(entities));
    }

    private static SnapshotEntity entity(String name, SnapshotForeignKey... foreignKeys) {
        return new SnapshotEntity(name, "table", List.of(), List.of(), List.of(foreignKeys));
    }

    /** The orders table, holding the two keys that point at customers. */
    private static SnapshotEntity ordersWithKeys() {
        return entity("orders",
                new SnapshotForeignKey("customer_id", "customers", "id"),
                new SnapshotForeignKey("shipped_to", "customers", "id"));
    }

    private static SchemaChange removedEntity(String entity) {
        return new SchemaChange(Type.ENTITY_REMOVED, Severity.BREAKING, entity, null, "table",
                null, null);
    }

    private static SchemaChange removedColumn(String entity, String column) {
        return new SchemaChange(Type.COLUMN_REMOVED, Severity.BREAKING, entity, column,
                "varchar(120) nullable", null, null);
    }
}
