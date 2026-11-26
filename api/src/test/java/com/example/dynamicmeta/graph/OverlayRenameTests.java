package com.example.dynamicmeta.graph;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.dynamicmeta.graph.Overlay.Label;
import com.example.dynamicmeta.graph.Overlay.ManualRelationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-006: when a reviewer confirms that a column or entity was renamed, the
 * overlay's edits move to the new id instead of being orphaned. A label key,
 * a decision id side or a manual relationship end is rewritten when it
 * equals the old id or sits under it after a dot — so renaming an entity
 * moves its columns with it — while ids that merely begin with the same
 * characters stay where they are, and a label the new id already has wins
 * over the moved one.
 */
class OverlayRenameTests {

    private static final String ORDER = "shop:order";
    private static final String ORDERS = "shop:orders";
    private static final String ORDER_ID = "shop:order.id";
    private static final String ORDER_TOTAL = "shop:order.total";
    private static final String CUSTOMER_ID = "shop:customer.id";

    @TempDir
    Path tempDir;

    @Test
    void columnRenameMovesItsLabelAndTheDecisionsUsingIt() {
        Overlay overlay = Overlay.empty()
                .withLabel(ORDER_TOTAL, "Total", "Order total")
                .withLabel(CUSTOMER_ID, "Customer", null)
                .withDecision(ORDER_TOTAL + "->" + CUSTOMER_ID, "confirmed")
                .withDecision(CUSTOMER_ID + "->" + ORDER_TOTAL, "rejected")
                .withDecision(ORDER_ID + "->" + CUSTOMER_ID, "confirmed");

        Overlay renamed = overlay.withRenamedId(ORDER_TOTAL, "shop:order.amount");

        assertThat(renamed.labels())
                .containsOnlyKeys("shop:order.amount", CUSTOMER_ID)
                .containsEntry("shop:order.amount", new Label("Total", "Order total"));
        // Each side of "->" is rewritten on its own: the decisions that
        // named the renamed column follow it, the one that did not stays.
        assertThat(renamed.relationshipDecisions()).containsOnlyKeys(
                "shop:order.amount->shop:customer.id",
                "shop:customer.id->shop:order.amount",
                ORDER_ID + "->" + CUSTOMER_ID);
        assertThat(renamed.relationshipDecisions())
                .containsEntry("shop:order.amount->shop:customer.id", "confirmed")
                .containsEntry("shop:customer.id->shop:order.amount", "rejected");
    }

    @Test
    void entityRenameMovesEntityAndColumnLabelsAndManualRelationshipEnds() {
        Overlay overlay = Overlay.empty()
                .withLabel(ORDER, "Order", "One purchase")
                .withLabel(ORDER_ID, "Number", "Order number")
                .withLabel(ORDER_TOTAL, "Total", null)
                .withLabel(CUSTOMER_ID, "Customer", null)
                .withDecision(ORDER_TOTAL + "->" + CUSTOMER_ID, "rejected")
                .withManualRelationship(ORDER_ID, CUSTOMER_ID);

        Overlay renamed = overlay.withRenamedId(ORDER, ORDERS);

        assertThat(renamed.labels()).containsOnlyKeys(
                ORDERS, "shop:orders.id", "shop:orders.total", CUSTOMER_ID);
        assertThat(renamed.labels().get(ORDERS)).isEqualTo(new Label("Order", "One purchase"));
        assertThat(renamed.labels().get("shop:orders.id"))
                .isEqualTo(new Label("Number", "Order number"));
        assertThat(renamed.relationshipDecisions())
                .containsOnlyKeys("shop:orders.total->shop:customer.id")
                .containsValue("rejected");
        // One end of the manual relationship is a column of the renamed
        // entity, the other is not: only the first end moves.
        assertThat(renamed.manualRelationships())
                .containsExactly(new ManualRelationship("shop:orders.id", CUSTOMER_ID));
    }

    @Test
    void renamingShopOrderLeavesShopOrdersAlone() {
        Overlay overlay = Overlay.empty()
                .withLabel(ORDER, "Order", null)
                .withLabel(ORDERS, "Orders", "A different table")
                .withLabel("shop:orders.id", "Number", null)
                .withDecision(ORDER_ID + "->shop:orders.id", "confirmed")
                .withManualRelationship(ORDER_ID, "shop:orders.id");

        Overlay renamed = overlay.withRenamedId(ORDER, "shop:purchase");

        // "shop:orders" and "shop:orders.id" neither equal "shop:order" nor
        // start with "shop:order." — they stay exactly as they were.
        assertThat(renamed.labels()).containsOnlyKeys(
                "shop:purchase", ORDERS, "shop:orders.id");
        assertThat(renamed.labels().get("shop:purchase"))
                .isEqualTo(new Label("Order", null));
        assertThat(renamed.labels().get(ORDERS))
                .isEqualTo(new Label("Orders", "A different table"));
        assertThat(renamed.labels().get("shop:orders.id"))
                .isEqualTo(new Label("Number", null));
        assertThat(renamed.relationshipDecisions())
                .containsOnlyKeys("shop:purchase.id->shop:orders.id")
                .containsEntry("shop:purchase.id->shop:orders.id", "confirmed");
        assertThat(renamed.manualRelationships())
                .containsExactly(new ManualRelationship("shop:purchase.id", "shop:orders.id"));
    }

    @Test
    void anExistingLabelForTheNewIdWinsAndTheMovedOneIsDropped() {
        Overlay overlay = Overlay.empty()
                .withLabel(ORDER, "Old", "Written before the rename")
                .withLabel(ORDERS, "Orders", "Written for the new name")
                .withLabel(ORDER_ID, "Old number", null)
                .withLabel("shop:orders.id", "Number", "Kept");

        Overlay renamed = overlay.withRenamedId(ORDER, ORDERS);

        assertThat(renamed.labels()).containsOnlyKeys(ORDERS, "shop:orders.id");
        assertThat(renamed.labels().get(ORDERS))
                .isEqualTo(new Label("Orders", "Written for the new name"));
        assertThat(renamed.labels().get("shop:orders.id"))
                .isEqualTo(new Label("Number", "Kept"));
    }

    @Test
    void idsAreLowerCasedAndATouchlessRenameIsANoOp() {
        Overlay overlay = Overlay.empty().withLabel(ORDER_ID, "Number", null);

        Overlay renamed = overlay.withRenamedId("Shop:Order.Id", "SHOP:ORDER.NUMBER");
        assertThat(renamed.labels()).containsOnlyKeys("shop:order.number");

        // The same id in another case, and an id the overlay says nothing
        // about, both return the overlay itself.
        assertThat(overlay.withRenamedId(ORDER_ID, " shop:order.id ")).isSameAs(overlay);
        assertThat(overlay.withRenamedId("billing:invoice", "billing:bill")).isSameAs(overlay);
    }

    @Test
    void blankIdsAreRejected() {
        Overlay overlay = Overlay.empty();

        assertThatThrownBy(() -> overlay.withRenamedId("   ", ORDERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> overlay.withRenamedId(ORDER, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void storeRenamePersistsImmediately() {
        Path file = tempDir.resolve("schema-overlay.json");
        OverlayStore store = new OverlayStore(file);
        store.setLabel(ORDER, "Order", null);
        store.setLabel(ORDER_TOTAL, "Total", null);
        store.decide(ORDER_TOTAL + "->" + CUSTOMER_ID, "confirmed");
        store.addManual(ORDER_TOTAL, CUSTOMER_ID);

        store.renameId(ORDER, ORDERS);

        Overlay reloaded = new OverlayStore(file).overlay();
        assertThat(reloaded.labels()).containsOnlyKeys(ORDERS, "shop:orders.total");
        assertThat(reloaded.labels().get(ORDERS)).isEqualTo(new Label("Order", null));
        assertThat(reloaded.relationshipDecisions())
                .containsOnlyKeys("shop:orders.total->shop:customer.id")
                .containsEntry("shop:orders.total->shop:customer.id", "confirmed");
        assertThat(reloaded.manualRelationships())
                .containsExactly(new ManualRelationship("shop:orders.total", CUSTOMER_ID));
    }

    @Test
    void aRenameThatTouchesNothingDoesNotWriteTheFile() {
        Path file = tempDir.resolve("schema-overlay.json");
        OverlayStore store = new OverlayStore(file);

        store.renameId(ORDER, ORDERS);

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void anInvalidRenameLeavesTheStoreUnchanged() {
        Path file = tempDir.resolve("schema-overlay.json");
        OverlayStore store = new OverlayStore(file);
        store.setLabel(ORDER, "Order", null);
        Overlay before = store.overlay();

        assertThatThrownBy(() -> store.renameId(" ", ORDERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThat(store.overlay()).isSameAs(before);
    }
}
