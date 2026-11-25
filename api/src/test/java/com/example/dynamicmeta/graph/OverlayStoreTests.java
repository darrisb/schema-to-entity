package com.example.dynamicmeta.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.dynamicmeta.graph.Overlay.Label;
import com.example.dynamicmeta.graph.Overlay.ManualRelationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GR-005: the overlay store keeps a person's edits in one JSON file — an
 * empty overlay when the file is missing, a failure naming the file when it
 * is not a readable overlay, and every mutation persisted immediately, so a
 * new store instance sees it.
 */
class OverlayStoreTests {

    private static final String RELATIONSHIP =
            "orders:orderitems.productid->catalog:products.id";
    private static final String FROM = "Orders:OrderItems.ProductId";
    private static final String TO = "Catalog:Products.Id";

    @TempDir
    Path tempDir;

    private Path file;

    @BeforeEach
    void nameTheOverlayFile() {
        file = tempDir.resolve("schema-overlay.json");
    }

    @Test
    void missingFileLoadsEmptyOverlayWithoutCreatingIt() {
        OverlayStore store = new OverlayStore(file);

        Overlay loaded = store.load();

        assertThat(loaded.labels()).isEmpty();
        assertThat(loaded.relationshipDecisions()).isEmpty();
        assertThat(loaded.manualRelationships()).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void saveThenLoadRoundTripsEverySection() throws IOException {
        OverlayStore store = new OverlayStore(file);
        store.setLabel("Orders:Customers", "Customers", "People who order");
        store.setLabel("orders:customers.id", "Id", null);
        store.decide(RELATIONSHIP, "Confirmed");
        store.addManual(FROM, TO);

        String json = Files.readString(file);
        assertThat(json).startsWith("{\n")
                .contains("\"labels\"")
                .contains("\"relationshipDecisions\"")
                .contains("\"manualRelationships\"");

        Overlay loaded = new OverlayStore(file).load();

        assertThat(loaded.labels())
                .containsEntry("orders:customers", new Label("Customers", "People who order"))
                .containsEntry("orders:customers.id", new Label("Id", null));
        assertThat(loaded.relationshipDecisions())
                .containsEntry(RELATIONSHIP, "confirmed");
        assertThat(loaded.manualRelationships()).containsExactly(new ManualRelationship(
                "orders:orderitems.productid", "catalog:products.id"));
    }

    @Test
    void malformedFileFailsNamingTheFile() throws IOException {
        OverlayStore store = new OverlayStore(file);
        Files.writeString(file, "{ not json");

        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void constructingAStoreOverAMalformedFileFailsNamingTheFile() throws IOException {
        Files.writeString(file, "nope");

        assertThatThrownBy(() -> new OverlayStore(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void aFailedSaveLeavesTheStoreUnchanged() throws IOException {
        OverlayStore store = new OverlayStore(file);
        // A non-empty directory where the file should be makes the final move fail.
        Files.createDirectories(file.resolve("blocker"));

        assertThatThrownBy(() -> store.setLabel("orders:customers", "Customers", null))
                .isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(store.overlay().labels()).isEmpty();
    }

    @Test
    void savingCreatesMissingFolders() {
        Path nested = tempDir.resolve("data").resolve("overlays").resolve("schema-overlay.json");
        OverlayStore store = new OverlayStore(nested);

        store.setLabel("orders:customers", "Customers", null);

        assertThat(Files.exists(nested)).isTrue();
        assertThat(new OverlayStore(nested).overlay().labels()).containsKey("orders:customers");
    }

    @Test
    void labelMutatorsPersistImmediately() {
        OverlayStore store = new OverlayStore(file);
        store.setLabel("orders:customers", "Customers", "People who order");

        assertThat(new OverlayStore(file).overlay().labels())
                .containsEntry("orders:customers", new Label("Customers", "People who order"));

        new OverlayStore(file).removeLabel("orders:customers");

        assertThat(new OverlayStore(file).overlay().labels()).isEmpty();
    }

    @Test
    void decisionMutatorsPersistImmediately() {
        OverlayStore store = new OverlayStore(file);
        store.decide(RELATIONSHIP, "confirmed");

        assertThat(new OverlayStore(file).overlay().relationshipDecisions())
                .containsEntry(RELATIONSHIP, "confirmed");

        new OverlayStore(file).clearDecision(RELATIONSHIP);

        assertThat(new OverlayStore(file).overlay().relationshipDecisions()).isEmpty();
    }

    @Test
    void manualRelationshipMutatorsPersistImmediately() {
        OverlayStore store = new OverlayStore(file);
        store.addManual(FROM, TO);
        // The same pair in another case is the same relationship, not a second one.
        store.addManual(FROM.toLowerCase(), TO.toLowerCase());

        assertThat(new OverlayStore(file).overlay().manualRelationships())
                .containsExactly(new ManualRelationship(
                        "orders:orderitems.productid", "catalog:products.id"));

        new OverlayStore(file).removeManual(RELATIONSHIP);

        assertThat(new OverlayStore(file).overlay().manualRelationships()).isEmpty();
    }

    @Test
    void invalidDecisionValuesAreRejectedWithoutSaving() {
        OverlayStore store = new OverlayStore(file);

        assertThatThrownBy(() -> store.decide(RELATIONSHIP, "maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmed");
        // "proposed" is a review state of a relationship, not a stored decision:
        // the overlay only ever holds what a person has decided.
        assertThatThrownBy(() -> store.decide(RELATIONSHIP, "proposed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejected");

        assertThat(store.overlay().relationshipDecisions()).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void oversizeAndBlankLabelsAreRejectedWithoutSaving() {
        OverlayStore store = new OverlayStore(file);

        assertThatThrownBy(() -> store.setLabel("orders:customers", "x".repeat(201), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
        assertThatThrownBy(() -> store.setLabel(
                "orders:customers", "Customers", "y".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2000");
        assertThatThrownBy(() -> store.setLabel("orders:customers", "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThat(store.overlay().labels()).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void manualRelationshipsMustJoinTwoColumnIds() {
        OverlayStore store = new OverlayStore(file);

        assertThatThrownBy(() -> store.addManual("orders:customers", "catalog:products.id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column");

        assertThat(store.overlay().manualRelationships()).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void aHandEditedFileWithAnInvalidDecisionFailsNamingTheFile() throws IOException {
        Files.writeString(file, "{\"relationshipDecisions\": {\"" + RELATIONSHIP
                + "\": \"maybe\"}}");

        assertThatThrownBy(() -> new OverlayStore(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void unknownJsonPropertiesAreIgnored() throws IOException {
        Files.writeString(file, "{\"labels\": {}, \"somethingNew\": 1}");

        Overlay loaded = new OverlayStore(file).load();

        assertThat(loaded.labels()).isEmpty();
        assertThat(loaded.relationshipDecisions()).isEmpty();
        assertThat(loaded.manualRelationships()).isEmpty();
    }

    @Test
    void savesLeaveNoTemporaryFilesBehind() {
        OverlayStore store = new OverlayStore(file);
        store.setLabel("orders:customers", "Customers", null);
        store.decide(RELATIONSHIP, "rejected");
        store.addManual(FROM, TO);

        try (Stream<Path> entries = Files.list(tempDir)) {
            assertThat(entries).containsExactly(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
