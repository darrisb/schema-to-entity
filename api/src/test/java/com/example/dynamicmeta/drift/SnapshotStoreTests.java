package com.example.dynamicmeta.drift;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotColumn;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-002: the snapshot store keeps one accepted baseline JSON file per source
 * in a folder — read back empty when missing, written atomically, superseded
 * baselines archived under {@code history/<source>/} with only the newest ten
 * kept, unsafe source names refused, and a malformed file reported by name and
 * left alone. These tests write only into a temporary folder; nothing
 * connects to a database.
 */
class SnapshotStoreTests {

    private static final Instant T1 = Instant.parse("2026-11-25T10:00:00Z");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Reading a baseline
    // ------------------------------------------------------------------

    @Test
    void missingBaselineIsEmptyAndCreatesNothing() {
        Path folder = tempDir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(folder);

        assertThat(store.baseline("shop")).isEmpty();
        assertThat(Files.exists(folder)).isFalse();
    }

    @Test
    void saveThenLoadRoundTripsTheWholeSnapshot() throws IOException {
        Path folder = tempDir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(folder);
        SchemaSnapshot snapshot = snapshot("shop", T1);

        store.saveBaseline(snapshot);

        // A new store instance over the same folder sees the baseline.
        assertThat(new SnapshotStore(folder).baseline("shop")).contains(snapshot);
        assertThat(folder.resolve("shop.json")).exists();
        // Pretty printed, with takenAt as a plain ISO-8601 string.
        String json = Files.readString(folder.resolve("shop.json"));
        assertThat(json).contains("\"source\" : \"shop\"");
        assertThat(json).contains("\"takenAt\" : \"2026-11-25T10:00:00Z\"");
        // The atomic move leaves no temporary files behind.
        try (Stream<Path> left = Files.list(folder)) {
            assertThat(left.filter(p -> p.getFileName().toString().endsWith(".tmp")))
                    .isEmpty();
        }
    }

    @Test
    void saveCreatesMissingFoldersAndKeepsSourcesApart() {
        Path folder = tempDir.resolve("deep/nested/snapshots");
        SnapshotStore store = new SnapshotStore(folder.toAbsolutePath().toString());

        store.saveBaseline(snapshot("alpha", T1));
        store.saveBaseline(snapshot("beta", T1));

        assertThat(folder.resolve("alpha.json")).exists();
        assertThat(folder.resolve("beta.json")).exists();
        assertThat(store.baseline("alpha")).hasValueSatisfying(s ->
                assertThat(s.source()).isEqualTo("alpha"));
        assertThat(store.baseline("beta")).hasValueSatisfying(s ->
                assertThat(s.source()).isEqualTo("beta"));
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Test
    void savingTwiceArchivesTheFirstBaselineUnderItsTakenAt() throws IOException {
        Path folder = tempDir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(folder);
        SchemaSnapshot first = snapshot("shop", T1);
        SchemaSnapshot second = snapshot("shop", T1.plusSeconds(60));

        store.saveBaseline(first);
        // The baseline file exactly as it stood before the second save.
        String original = Files.readString(folder.resolve("shop.json"));

        store.saveBaseline(second);

        Path archived = folder.resolve("history").resolve("shop")
                .resolve(STAMP.format(T1) + ".json");
        assertThat(archived).exists();
        assertThat(Files.readString(archived)).isEqualTo(original);
        // The baseline itself is the new snapshot, not the archived one.
        assertThat(store.baseline("shop")).contains(second);
    }

    @Test
    void historyKeepsOnlyTheNewestTenBaselines() throws IOException {
        SnapshotStore store = new SnapshotStore(tempDir);

        // Twelve saves archive snapshots 1..11; only the ten newest survive.
        Instant takenAt = T1;
        SchemaSnapshot last = null;
        for (int i = 0; i < 12; i++) {
            last = snapshot("shop", takenAt);
            store.saveBaseline(last);
            takenAt = takenAt.plusSeconds(3600);
        }

        List<Path> history;
        try (Stream<Path> files = Files.list(tempDir.resolve("history").resolve("shop"))) {
            history = files.toList();
        }
        assertThat(history).hasSize(10);
        assertThat(history).noneMatch(p -> p.getFileName().toString()
                .equals(STAMP.format(T1) + ".json"));
        assertThat(history).allMatch(p -> p.getFileName().toString().endsWith(".json"));
        assertThat(store.baseline("shop")).contains(last);
    }

    // ------------------------------------------------------------------
    // Source name safety
    // ------------------------------------------------------------------

    @Test
    void unsafeSourceNamesAreRejectedBeforeAnyFileIsTouched() {
        SnapshotStore store = new SnapshotStore(tempDir.resolve("snapshots"));

        for (String bad : List.of("../x", "..", "Shop", "my_source", "a".repeat(41),
                "", "orders/", "orders\\x", "orders%2f")) {
            assertThatThrownBy(() -> store.baseline(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[a-z0-9-]{1,40}");
            assertThatThrownBy(() -> store.saveBaseline(snapshot(bad, T1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.baseline(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir).isEmptyDirectory();
    }

    // ------------------------------------------------------------------
    // Malformed files
    // ------------------------------------------------------------------

    @Test
    void malformedBaselineFailsNamingTheFileAndIsNeverDeleted() throws IOException {
        Path file = tempDir.resolve("shop.json");
        Files.writeString(file, "{ this is not a snapshot ");
        SnapshotStore store = new SnapshotStore(tempDir);

        assertThatThrownBy(() -> store.baseline("shop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.json");
        assertThat(file).exists();
    }

    @Test
    void savingOverAMalformedBaselineIsRefusedAndChangesNothing() throws IOException {
        Path file = tempDir.resolve("shop.json");
        Files.writeString(file, "{ this is not a snapshot ");
        SnapshotStore store = new SnapshotStore(tempDir);

        assertThatThrownBy(() -> store.saveBaseline(snapshot("shop", T1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.json");

        assertThat(file).exists();
        assertThat(Files.readString(file)).isEqualTo("{ this is not a snapshot ");
        assertThat(tempDir.resolve("history")).doesNotExist();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A small but full snapshot: one table, two columns, one key, one foreign key. */
    private static SchemaSnapshot snapshot(String source, Instant takenAt) {
        return new SchemaSnapshot(source, "0".repeat(64), takenAt, "h2", List.of(
                new SnapshotEntity("orders", "table",
                        List.of(new SnapshotColumn("id", "bigint", ColumnCategory.NUMBER, false),
                                new SnapshotColumn("user_id", "bigint", ColumnCategory.NUMBER, true)),
                        List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "users", "id")))));
    }
}
