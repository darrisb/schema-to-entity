package com.example.dynamicmeta.drift;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.ForeignKeyModel;
import com.example.dynamicmeta.data.SourceSchema;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-001: {@link SnapshotBuilder} produces a canonical snapshot of a schema and
 * a fingerprint that changes exactly when the structure changes. These tests
 * build {@link SourceSchema}s in memory; nothing connects to a database.
 */
class SnapshotBuilderTests {

    private static final Instant T1 = Instant.parse("2026-11-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2020-01-01T00:00:00Z");

    private final SnapshotBuilder builder = new SnapshotBuilder();

    // ------------------------------------------------------------------
    // Ordering must never affect the result
    // ------------------------------------------------------------------

    @Test
    void sameStructureInADifferentOrderHasTheSameFingerprint() {
        // orders has three columns and two declared foreign keys; users two
        // columns. The two maps carry the SAME structure but with the entities,
        // the columns and the foreign keys deliberately fed in in different
        // orders, so an implementation that failed to sort would hash the two
        // differently and fail here.
        Map<String, EntityModel> firstOrder = new LinkedHashMap<>();
        firstOrder.put("orders", table("orders",
                List.of(column("id", "bigint", false), column("user_id", "bigint", false),
                        column("ship_id", "bigint", true)),
                List.of("id"),
                List.of(declared("user_id", "users", "id"),
                        declared("ship_id", "shipping", "id"))));
        firstOrder.put("users", table("users",
                List.of(column("id", "bigint", false), column("email", "varchar(120)", true)),
                List.of("id"), List.of()));

        Map<String, EntityModel> reversedOrder = new LinkedHashMap<>();
        reversedOrder.put("users", table("users",
                List.of(column("email", "varchar(120)", true), column("id", "bigint", false)),
                List.of("id"), List.of()));
        reversedOrder.put("orders", table("orders",
                List.of(column("ship_id", "bigint", true), column("id", "bigint", false),
                        column("user_id", "bigint", false)),
                List.of("id"),
                List.of(declared("ship_id", "shipping", "id"),
                        declared("user_id", "users", "id"))));

        SchemaSnapshot first = builder.build("shop", new SourceSchema("h2", null, "PUBLIC", firstOrder), T1);
        SchemaSnapshot second = builder.build("shop", new SourceSchema("h2", null, "PUBLIC", reversedOrder), T1);

        // Same structure reached by different order -> identical fingerprint.
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        // ... and the snapshots themselves are equal, not just their hashes.
        assertThat(first.entities()).isEqualTo(second.entities());

        // Sorting is visibly applied: entities, columns and foreign keys come
        // back in lower-cased name order regardless of the input order.
        assertThat(first.entities()).extracting(SnapshotEntity::name)
                .containsExactly("orders", "users");
        SnapshotEntity orders = first.entities().getFirst();
        assertThat(orders.columns()).extracting(c -> c.name())
                .containsExactly("id", "ship_id", "user_id");
        assertThat(orders.foreignKeys()).extracting(SnapshotForeignKey::column)
                .containsExactly("ship_id", "user_id");
    }

    // ------------------------------------------------------------------
    // Structural changes must change the fingerprint
    // ------------------------------------------------------------------

    @Test
    void addingAColumnChangesTheFingerprint() {
        SchemaSnapshot before = builder.build("shop", oneTable(
                List.of(column("id", "bigint", false)), List.of("id"), List.of()), T1);
        SchemaSnapshot after = builder.build("shop", oneTable(
                List.of(column("id", "bigint", false), column("email", "varchar(120)", true)),
                List.of("id"), List.of()), T1);

        assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
    }

    @Test
    void changingAColumnTypeChangesTheFingerprint() {
        SchemaSnapshot bigint = builder.build("shop", oneTable(
                List.of(column("id", "bigint", false)), List.of("id"), List.of()), T1);
        SchemaSnapshot varchar = builder.build("shop", oneTable(
                List.of(column("id", "varchar(50)", false)), List.of("id"), List.of()), T1);

        assertThat(varchar.fingerprint()).isNotEqualTo(bigint.fingerprint());
    }

    @Test
    void changingNullabilityChangesTheFingerprint() {
        SchemaSnapshot notNull = builder.build("shop", oneTable(
                List.of(column("id", "bigint", false)), List.of("id"), List.of()), T1);
        SchemaSnapshot nullable = builder.build("shop", oneTable(
                List.of(column("id", "bigint", true)), List.of("id"), List.of()), T1);

        assertThat(nullable.fingerprint()).isNotEqualTo(notNull.fingerprint());
    }

    @Test
    void changingTheKeyColumnsChangesTheFingerprint() {
        SchemaSnapshot singleKey = builder.build("shop", oneTable(
                List.of(column("id", "bigint", false), column("code", "varchar(20)", false)),
                List.of("id"), List.of()), T1);
        SchemaSnapshot compositeKey = builder.build("shop", oneTable(
                List.of(column("id", "bigint", false), column("code", "varchar(20)", false)),
                List.of("id", "code"), List.of()), T1);

        assertThat(compositeKey.fingerprint()).isNotEqualTo(singleKey.fingerprint());
    }

    @Test
    void changingADeclaredForeignKeyChangesTheFingerprint() {
        List<ColumnModel> columns = List.of(column("id", "bigint", false),
                column("user_id", "bigint", false));
        SchemaSnapshot toUsers = builder.build("shop", oneTable(
                columns, List.of("id"), List.of(declared("user_id", "users", "id"))), T1);
        SchemaSnapshot toCustomers = builder.build("shop", oneTable(
                columns, List.of("id"), List.of(declared("user_id", "customers", "id"))), T1);

        assertThat(toCustomers.fingerprint()).isNotEqualTo(toUsers.fingerprint());
    }

    // ------------------------------------------------------------------
    // Volatile data must not affect the fingerprint
    // ------------------------------------------------------------------

    @Test
    void takenAtDoesNotAffectTheFingerprint() {
        SourceSchema schema = oneTable(List.of(column("id", "bigint", false)),
                List.of("id"), List.of());
        SchemaSnapshot early = builder.build("shop", schema, T1);
        SchemaSnapshot late = builder.build("shop", schema, T2);

        assertThat(late.fingerprint()).isEqualTo(early.fingerprint());
        // takenAt is still recorded on the snapshot, and it really differs.
        assertThat(early.takenAt()).isEqualTo(T1);
        assertThat(late.takenAt()).isEqualTo(T2);
    }

    @Test
    void inferredForeignKeysAreExcludedFromTheSnapshotAndTheFingerprint() {
        List<ColumnModel> columns = List.of(column("id", "bigint", false),
                column("user_id", "bigint", false));
        // A declared foreign key, plus an inferred one that can appear or vanish
        // without the schema changing.
        SchemaSnapshot withInferred = builder.build("shop", oneTable(columns, List.of("id"),
                List.of(declared("user_id", "users", "id"),
                        inferred("id", "other", "id"))), T1);
        SchemaSnapshot declaredOnly = builder.build("shop", oneTable(columns, List.of("id"),
                List.of(declared("user_id", "users", "id"))), T1);

        assertThat(withInferred.fingerprint()).isEqualTo(declaredOnly.fingerprint());
        // The inferred foreign key never reaches the snapshot.
        List<SnapshotForeignKey> foreignKeys = withInferred.entities().getFirst().foreignKeys();
        assertThat(foreignKeys).hasSize(1);
        assertThat(foreignKeys.getFirst()).isEqualTo(new SnapshotForeignKey("user_id", "users", "id"));
    }

    @Test
    void caseOnlyNameDifferencesDoNotChangeTheFingerprint() {
        // Every identifier differs only in case, and the driver reports the
        // table name as USERS vs users; names are lower-cased before hashing.
        SourceSchema lower = new SourceSchema("h2", null, "PUBLIC", Map.of(
                "users", new EntityModel("users", "table",
                        List.of(new ColumnModel("id", "bigint", ColumnCategory.NUMBER, false)),
                        List.of("id"),
                        List.of(new ForeignKeyModel(null, "user_id", "customers", "id",
                                ForeignKeyModel.DECLARED)), List.of())));
        SourceSchema upper = new SourceSchema("h2", null, "PUBLIC", Map.of(
                "users", new EntityModel("USERS", "TABLE",
                        List.of(new ColumnModel("ID", "bigint", ColumnCategory.NUMBER, false)),
                        List.of("ID"),
                        List.of(new ForeignKeyModel(null, "USER_ID", "CUSTOMERS", "ID",
                                ForeignKeyModel.DECLARED)), List.of())));

        assertThat(builder.build("shop", upper, T1).fingerprint())
                .isEqualTo(builder.build("shop", lower, T1).fingerprint());
    }

    // ------------------------------------------------------------------
    // Canonical encoding must be unambiguous (regression guard)
    // ------------------------------------------------------------------

    @Test
    void fieldBoundariesAreUnambiguous() {
        // Without length-prefixed fields a naive join collides here:
        // "ab" + "c" == "a" + "bc". The two structures differ, so their
        // fingerprints must differ too.
        SchemaSnapshot first = builder.build("shop", oneTable(
                List.of(column("ab", "c", true)), List.of(), List.of()), T1);
        SchemaSnapshot second = builder.build("shop", oneTable(
                List.of(column("a", "bc", true)), List.of(), List.of()), T1);

        assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test
    void fingerprintIsStableLowercaseHex() {
        SourceSchema schema = oneTable(List.of(column("id", "bigint", false)),
                List.of("id"), List.of());
        SchemaSnapshot snapshot = builder.build("shop", schema, T1);

        assertThat(snapshot.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(builder.build("shop", schema, T1).fingerprint())
                .isEqualTo(snapshot.fingerprint());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SourceSchema oneTable(List<ColumnModel> columns, List<String> keyColumns,
                                         List<ForeignKeyModel> foreignKeys) {
        return new SourceSchema("h2", null, "PUBLIC",
                Map.of("things", table("things", columns, keyColumns, foreignKeys)));
    }

    private static EntityModel table(String name, List<ColumnModel> columns,
                                     List<String> keyColumns, List<ForeignKeyModel> foreignKeys) {
        return new EntityModel(name, "table", columns, keyColumns, foreignKeys, List.of());
    }

    private static ColumnModel column(String name, String type, boolean nullable) {
        return new ColumnModel(name, type, ColumnCategory.fromTypeName(type), nullable);
    }

    private static ForeignKeyModel declared(String column, String referencedTable,
                                            String referencedColumn) {
        return new ForeignKeyModel(null, column, referencedTable, referencedColumn,
                ForeignKeyModel.DECLARED);
    }

    private static ForeignKeyModel inferred(String column, String referencedTable,
                                            String referencedColumn) {
        return new ForeignKeyModel(null, column, referencedTable, referencedColumn,
                ForeignKeyModel.INFERRED);
    }
}
