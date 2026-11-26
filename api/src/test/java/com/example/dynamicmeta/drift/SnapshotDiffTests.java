package com.example.dynamicmeta.drift;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.drift.SchemaChange.Severity;
import com.example.dynamicmeta.drift.SchemaChange.Type;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotColumn;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * DR-003: {@link SnapshotDiff} compares a baseline snapshot with the current
 * one and classifies every difference. These tests build both snapshots in
 * memory; nothing here reads from or writes to a database.
 */
class SnapshotDiffTests {

    private static final Instant T1 = Instant.parse("2026-11-25T10:00:00Z");

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    @Test
    void anEntityThatAppearsIsAdditive() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", column("id", "bigint", false))),
                snapshot(entity("users", column("id", "bigint", false)),
                        entity("audit_log", column("id", "bigint", false))));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.ENTITY_ADDED))
                .extracting(SchemaChange::severity, SchemaChange::entity, SchemaChange::column,
                        SchemaChange::before, SchemaChange::after, SchemaChange::relatedTo)
                .containsExactly(Severity.ADDITIVE, "audit_log", null, null, "table", null);
    }

    @Test
    void anEntityThatDisappearsIsBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("id", "bigint", false)),
                        List.of("id"), List.of())),
                snapshot());

        // The table is gone, so its columns are not filed one by one.
        assertThat(changes).extracting(SchemaChange::type).containsExactly(Type.ENTITY_REMOVED);
        assertThat(single(changes, Type.ENTITY_REMOVED))
                .extracting(SchemaChange::severity, SchemaChange::entity, SchemaChange::column,
                        SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.BREAKING, "users", null, "table", null);
    }

    @Test
    void anEntityThatChangesKindIsBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", "table", List.of(column("id", "bigint", false)),
                        List.of("id"), List.of())),
                snapshot(entity("users", "view", List.of(column("id", "bigint", false)),
                        List.of("id"), List.of())));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.ENTITY_KIND_CHANGED))
                .extracting(SchemaChange::severity, SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.BREAKING, "table", "view");
    }

    // ------------------------------------------------------------------
    // Columns: added, removed
    // ------------------------------------------------------------------

    @Test
    void aNullableColumnThatAppearsIsAdditive() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", column("id", "bigint", false))),
                snapshot(entity("users", column("id", "bigint", false),
                        column("nickname", "varchar(60)", true))));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_ADDED))
                .extracting(SchemaChange::severity, SchemaChange::entity, SchemaChange::column,
                        SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.ADDITIVE, "users", "nickname", null,
                        "varchar(60) nullable");
    }

    @Test
    void aNotNullColumnThatAppearsIsBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", column("id", "bigint", false))),
                snapshot(entity("users", column("id", "bigint", false),
                        column("tenant_id", "bigint", false))));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_ADDED))
                .extracting(SchemaChange::severity, SchemaChange::after)
                .containsExactly(Severity.BREAKING, "bigint not null");
    }

    @Test
    void aColumnThatDisappearsIsBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("id", "bigint", false),
                        column("nickname", "varchar(60)", true)), List.of("id"), List.of())),
                snapshot(entity("users", List.of(column("id", "bigint", false)),
                        List.of("id"), List.of())));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_REMOVED))
                .extracting(SchemaChange::severity, SchemaChange::column, SchemaChange::before,
                        SchemaChange::after)
                .containsExactly(Severity.BREAKING, "nickname", "varchar(60) nullable", null);
    }

    // ------------------------------------------------------------------
    // Column types
    // ------------------------------------------------------------------

    @Test
    void aTypeChangeAcrossCategoriesIsBreaking() {
        List<SchemaChange> changes = diffColumn("id", "bigint", true, "varchar(120)", true);

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED))
                .extracting(SchemaChange::severity, SchemaChange::entity, SchemaChange::column,
                        SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.BREAKING, "things", "id", "bigint", "varchar(120)");
    }

    @Test
    void aLargerLengthInTheSameFamilyIsAdditive() {
        List<SchemaChange> changes = diffColumn("email", "varchar(80)", true, "varchar(120)", true);

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED))
                .extracting(SchemaChange::severity, SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.ADDITIVE, "varchar(80)", "varchar(120)");
    }

    @Test
    void aSmallerLengthInTheSameFamilyIsBreaking() {
        List<SchemaChange> changes = diffColumn("email", "varchar(120)", true, "varchar(80)", true);

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED).severity()).isEqualTo(Severity.BREAKING);
    }

    @Test
    void aLargerPrecisionInTheSameFamilyIsAdditive() {
        List<SchemaChange> changes = diffColumn("total", "decimal(10,2)", true, "decimal(14,2)", true);

        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED).severity()).isEqualTo(Severity.ADDITIVE);
    }

    @Test
    void anOtherSameCategoryChangeIsReview() {
        // Same NUMBER category, different family: harmless or not, metadata cannot say.
        List<SchemaChange> changes = diffColumn("id", "int", true, "bigint", true);

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED).severity()).isEqualTo(Severity.REVIEW);
    }

    @Test
    void aScaleOnlyChangeIsReview() {
        List<SchemaChange> changes = diffColumn("total", "decimal(10,2)", true, "decimal(10,4)", true);

        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED).severity()).isEqualTo(Severity.REVIEW);
    }

    @Test
    void aTypeGainingABoundIsReview() {
        List<SchemaChange> changes = diffColumn("email", "varchar", true, "varchar(120)", true);

        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED).severity()).isEqualTo(Severity.REVIEW);
    }

    @Test
    void aTypeSpelledDifferentlyIsNotAChangeAtAll() {
        List<SchemaChange> changes = diffColumn("email", "VARCHAR ( 80 )", true, "varchar(80)", true);

        assertThat(changes).isEmpty();
    }

    // ------------------------------------------------------------------
    // Nullability
    // ------------------------------------------------------------------

    @Test
    void aColumnThatBecomesNotNullIsBreaking() {
        List<SchemaChange> changes = diffColumn("email", "varchar(120)", true, "varchar(120)", false);

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_NULLABILITY_CHANGED))
                .extracting(SchemaChange::severity, SchemaChange::column, SchemaChange::before,
                        SchemaChange::after)
                .containsExactly(Severity.BREAKING, "email", "nullable", "not null");
    }

    @Test
    void aColumnThatBecomesNullableIsAdditive() {
        List<SchemaChange> changes = diffColumn("email", "varchar(120)", false, "varchar(120)", true);

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.COLUMN_NULLABILITY_CHANGED).severity())
                .isEqualTo(Severity.ADDITIVE);
    }

    @Test
    void aTypeAndNullabilityChangeAreFiledSeparately() {
        List<SchemaChange> changes = diffColumn("email", "varchar(80)", false, "varchar(120)", true);

        assertThat(changes).extracting(SchemaChange::type)
                .containsExactlyInAnyOrder(Type.COLUMN_TYPE_CHANGED,
                        Type.COLUMN_NULLABILITY_CHANGED);
        assertThat(single(changes, Type.COLUMN_TYPE_CHANGED).severity()).isEqualTo(Severity.ADDITIVE);
        assertThat(single(changes, Type.COLUMN_NULLABILITY_CHANGED).severity())
                .isEqualTo(Severity.ADDITIVE);
    }

    // ------------------------------------------------------------------
    // Keys and declared foreign keys
    // ------------------------------------------------------------------

    @Test
    void changedKeyColumnsAreBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("code", "varchar(20)", false)), List.of("id"), List.of())),
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("code", "varchar(20)", false)), List.of("id", "code"), List.of())));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.KEY_COLUMNS_CHANGED))
                .extracting(SchemaChange::severity, SchemaChange::entity, SchemaChange::column,
                        SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.BREAKING, "orders", null, "id", "id, code");
    }

    @Test
    void anEntityWithNoKeyAtAllGettingOneIsBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("orders", List.of(column("id", "bigint", false)),
                        List.of(), List.of())),
                snapshot(entity("orders", List.of(column("id", "bigint", false)),
                        List.of("id"), List.of())));

        assertThat(single(changes, Type.KEY_COLUMNS_CHANGED))
                .extracting(SchemaChange::severity, SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.BREAKING, "none", "id");
    }

    @Test
    void anAddedDeclaredForeignKeyIsAdditive() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"), List.of())),
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "users", "id")))));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.FOREIGN_KEY_ADDED))
                .extracting(SchemaChange::severity, SchemaChange::entity, SchemaChange::column,
                        SchemaChange::before, SchemaChange::after)
                .containsExactly(Severity.ADDITIVE, "orders", "user_id", null, "users.id");
    }

    @Test
    void aRemovedDeclaredForeignKeyIsBreaking() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "users", "id")))),
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"), List.of())));

        assertThat(changes).hasSize(1);
        assertThat(single(changes, Type.FOREIGN_KEY_REMOVED))
                .extracting(SchemaChange::severity, SchemaChange::column, SchemaChange::before,
                        SchemaChange::after)
                .containsExactly(Severity.BREAKING, "user_id", "users.id", null);
    }

    @Test
    void aForeignKeyPointingElsewhereIsFiledAsRemovedAndAdded() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "users", "id")))),
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "customers", "id")))));

        assertThat(changes).extracting(SchemaChange::type)
                .containsExactlyInAnyOrder(Type.FOREIGN_KEY_REMOVED, Type.FOREIGN_KEY_ADDED);
        assertThat(changes).allSatisfy(change ->
                assertThat(change.column()).isEqualTo("user_id"));
    }

    // ------------------------------------------------------------------
    // Column rename candidates
    // ------------------------------------------------------------------

    @Test
    void emailBecomingEmailAddressIsARenameCandidate() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("id", "bigint", false),
                        column("email", "varchar(120)", true)), List.of("id"), List.of())),
                snapshot(entity("users", List.of(column("id", "bigint", false),
                        column("email_address", "varchar(120)", true)), List.of("id"), List.of())));

        // The candidate comes on top of the removal and the addition, not instead.
        assertThat(changes).extracting(SchemaChange::type).containsExactlyInAnyOrder(
                Type.COLUMN_REMOVED, Type.COLUMN_ADDED, Type.COLUMN_RENAME_CANDIDATE);

        SchemaChange candidate = single(changes, Type.COLUMN_RENAME_CANDIDATE);
        assertThat(candidate.severity()).isEqualTo(Severity.REVIEW);
        assertThat(candidate.entity()).isEqualTo("users");
        assertThat(candidate.column()).isEqualTo("email");
        assertThat(candidate.relatedTo()).isEqualTo("email_address");
        assertThat(candidate.before()).isEqualTo("varchar(120) nullable");
        assertThat(candidate.after()).isEqualTo("varchar(120) nullable");
    }

    @Test
    void similarNamesWithoutOneContainingTheOtherAreCandidates() {
        // "first_name" and "firstname" differ by one edit of ten characters: 0.9 similar.
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("first_name", "varchar(80)", true)),
                        List.of(), List.of())),
                snapshot(entity("users", List.of(column("firstname", "varchar(80)", true)),
                        List.of(), List.of())));

        assertThat(single(changes, Type.COLUMN_RENAME_CANDIDATE).relatedTo())
                .isEqualTo("firstname");
    }

    @Test
    void theCandidateComparisonIgnoresCase() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("EMAIL", "varchar(120)", true)),
                        List.of(), List.of())),
                snapshot(entity("users", List.of(column("email_address", "varchar(120)", true)),
                        List.of(), List.of())));

        assertThat(single(changes, Type.COLUMN_RENAME_CANDIDATE).column()).isEqualTo("EMAIL");
    }

    @Test
    void idBecomingCreatedAtIsNotARenameCandidate() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("things", List.of(column("id", "bigint", false)),
                        List.of(), List.of())),
                snapshot(entity("things", List.of(column("created_at", "bigint", false)),
                        List.of(), List.of())));

        // Two characters against ten: 0.1 similar, and neither name sits inside
        // the other, so the pairing is refused and only the two changes remain.
        assertThat(changes).extracting(SchemaChange::type)
                .containsExactlyInAnyOrder(Type.COLUMN_REMOVED, Type.COLUMN_ADDED);
        assertThat(changes).extracting(SchemaChange::type)
                .doesNotContain(Type.COLUMN_RENAME_CANDIDATE);
    }

    @Test
    void aColumnOfAnotherTypeIsNotARenameCandidate() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("email", "varchar(120)", true)),
                        List.of(), List.of())),
                snapshot(entity("users", List.of(column("email_address", "bigint", true)),
                        List.of(), List.of())));

        assertThat(changes).extracting(SchemaChange::type)
                .containsExactlyInAnyOrder(Type.COLUMN_REMOVED, Type.COLUMN_ADDED);
    }

    // ------------------------------------------------------------------
    // Entity rename candidates
    // ------------------------------------------------------------------

    @Test
    void anEntityWithTheSameColumnsUnderANewNameIsARenameCandidate() {
        List<SnapshotColumn> columns = List.of(column("id", "bigint", false),
                column("email", "varchar(120)", true), column("full_name", "varchar(80)", true));

        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("customers", columns, List.of("id"), List.of())),
                snapshot(entity("clients", columns, List.of("id"), List.of())));

        assertThat(changes).extracting(SchemaChange::type).containsExactlyInAnyOrder(
                Type.ENTITY_REMOVED, Type.ENTITY_ADDED, Type.ENTITY_RENAME_CANDIDATE);

        SchemaChange candidate = single(changes, Type.ENTITY_RENAME_CANDIDATE);
        assertThat(candidate.severity()).isEqualTo(Severity.REVIEW);
        assertThat(candidate.entity()).isEqualTo("customers");
        assertThat(candidate.column()).isNull();
        assertThat(candidate.relatedTo()).isEqualTo("clients");
        assertThat(candidate.before()).isEqualTo("table");
        assertThat(candidate.after()).isEqualTo("table");
    }

    @Test
    void entitiesSharingExactlyEightyPercentOfTheirColumnsAreCandidates() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("orders", List.of(column("id", "bigint", false),
                        column("total", "decimal(10,2)", true), column("state", "varchar(20)", true),
                        column("note", "text", true), column("owner_id", "bigint", true)),
                        List.of(), List.of())),
                snapshot(entity("purchases", List.of(column("id", "bigint", false),
                        column("total", "decimal(10,2)", true), column("state", "varchar(20)", true),
                        column("note", "text", true), column("customer_id", "bigint", true)),
                        List.of(), List.of())));

        assertThat(changes).anySatisfy(change -> {
            assertThat(change.type()).isEqualTo(Type.ENTITY_RENAME_CANDIDATE);
            assertThat(change.relatedTo()).isEqualTo("purchases");
        });
    }

    @Test
    void unrelatedEntitiesAreNotRenameCandidates() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("customers", List.of(column("id", "bigint", false),
                        column("email", "varchar(120)", true), column("full_name", "varchar(80)", true)),
                        List.of(), List.of())),
                snapshot(entity("audit_log", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", false), column("action", "varchar(40)", false)),
                        List.of(), List.of())));

        assertThat(changes).extracting(SchemaChange::type)
                .containsExactlyInAnyOrder(Type.ENTITY_REMOVED, Type.ENTITY_ADDED);
    }

    @Test
    void aSmallEntityIsNotARenameCandidateForALargeOne() {
        // Both of the small entity's names are shared, but that is 2 of 25: no.
        List<SnapshotColumn> large = new ArrayList<>();
        large.add(column("id", "bigint", false));
        large.add(column("email", "varchar(120)", true));
        for (int i = 0; i < 23; i++) {
            large.add(column("attr_" + i, "varchar(10)", true));
        }

        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("customers", List.of(column("id", "bigint", false),
                        column("email", "varchar(120)", true)), List.of(), List.of())),
                snapshot(entity("customers_archive", large, List.of(), List.of())));

        assertThat(changes).extracting(SchemaChange::type).doesNotContain(Type.ENTITY_RENAME_CANDIDATE);
    }

    // ------------------------------------------------------------------
    // Matching, ordering and the empty case
    // ------------------------------------------------------------------

    @Test
    void identicalSnapshotsGiveNoChanges() {
        SchemaSnapshot baseline = snapshot(
                entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "users", "id"))),
                entity("users", List.of(column("id", "bigint", false),
                        column("email", "varchar(120)", true)), List.of("id"), List.of()));
        SchemaSnapshot current = snapshot(
                entity("orders", List.of(column("id", "bigint", false),
                        column("user_id", "bigint", true)), List.of("id"),
                        List.of(new SnapshotForeignKey("user_id", "users", "id"))),
                entity("users", List.of(column("id", "bigint", false),
                        column("email", "varchar(120)", true)), List.of("id"), List.of()));

        assertThat(SnapshotDiff.diff(baseline, current)).isEmpty();
        // And the fingerprint-equal snapshots built by DR-001 diff to nothing.
        assertThat(SnapshotDiff.diff(baseline, baseline)).isEmpty();
    }

    @Test
    void namesThatDifferOnlyInCaseAreTheSameObject() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("USERS", List.of(column("ID", "bigint", false)),
                        List.of("ID"), List.of())),
                snapshot(entity("users", List.of(column("id", "bigint", false)),
                        List.of("id"), List.of())));

        assertThat(changes).isEmpty();
    }

    @Test
    void changesAreSortedByEntityThenColumnThenType() {
        List<SchemaChange> changes = SnapshotDiff.diff(
                snapshot(entity("users", List.of(column("id", "bigint", false),
                        column("email", "varchar(120)", true)), List.of("id"), List.of()),
                        entity("orders", List.of(column("id", "bigint", false)),
                                List.of("id"), List.of())),
                snapshot(entity("archive", List.of(column("id", "bigint", false)),
                                List.of(), List.of()),
                        entity("orders", List.of(column("id", "bigint", false),
                                column("note", "text", true)), List.of("id", "note"), List.of()),
                        entity("users", List.of(column("id", "bigint", false),
                                column("email", "varchar(200)", false)), List.of("id"), List.of())));

        // Entity-level changes (no column) lead their entity, columns follow in
        // name order, and same-named columns are ordered by change type.
        assertThat(changes).extracting(SchemaChange::entity, SchemaChange::column,
                        SchemaChange::type)
                .containsExactly(
                        tuple("archive", null, Type.ENTITY_ADDED),
                        tuple("orders", null, Type.KEY_COLUMNS_CHANGED),
                        tuple("orders", "note", Type.COLUMN_ADDED),
                        tuple("users", "email", Type.COLUMN_NULLABILITY_CHANGED),
                        tuple("users", "email", Type.COLUMN_TYPE_CHANGED));
    }

    @Test
    void theDiffDoesNotDependOnOrderOrSnapshotMetadata() {
        SchemaSnapshot baseline = snapshot(
                entity("users", List.of(column("id", "bigint", false)), List.of(), List.of()),
                entity("orders", List.of(column("id", "bigint", false)), List.of(), List.of()));
        SchemaSnapshot current = snapshot(
                entity("orders", List.of(column("id", "bigint", false),
                        column("note", "text", true)), List.of(), List.of()),
                entity("users", List.of(column("id", "bigint", false)), List.of(), List.of()));
        // The same structure with the entities and the columns fed in the other
        // order, under another source name, fingerprint, moment and dialect.
        SchemaSnapshot reordered = new SchemaSnapshot("other", "ff".repeat(32), Instant.EPOCH,
                "postgresql", List.of(
                        entity("users", List.of(column("id", "bigint", false)),
                                List.of(), List.of()),
                        entity("orders", List.of(column("note", "text", true),
                                column("id", "bigint", false)), List.of(), List.of())));

        assertThat(SnapshotDiff.diff(baseline, current))
                .isEqualTo(SnapshotDiff.diff(baseline, reordered));
        // Nothing volatile leaks into a change.
        assertThat(SnapshotDiff.diff(baseline, current)).allSatisfy(change -> {
            assertThat(change.entity()).isIn("orders", "users");
            String described = String.valueOf(change.before()) + " " + change.after();
            assertThat(described).doesNotContain("postgresql", "other", "ff");
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Diffs one column of one entity, changing only what the caller says. */
    private static List<SchemaChange> diffColumn(String name, String beforeType,
                                                 boolean beforeNullable, String afterType,
                                                 boolean afterNullable) {
        return SnapshotDiff.diff(
                snapshot(entity("things", List.of(column(name, beforeType, beforeNullable)),
                        List.of(), List.of())),
                snapshot(entity("things", List.of(column(name, afterType, afterNullable)),
                        List.of(), List.of())));
    }

    private static SchemaChange single(List<SchemaChange> changes, Type type) {
        List<SchemaChange> matches = changes.stream().filter(change -> change.type() == type).toList();
        assertThat(matches).as("changes of type %s", type).hasSize(1);
        return matches.getFirst();
    }

    private static SchemaSnapshot snapshot(SnapshotEntity... entities) {
        return new SchemaSnapshot("shop", "0".repeat(64), T1, "h2", List.of(entities));
    }

    private static SnapshotEntity entity(String name, SnapshotColumn... columns) {
        return entity(name, "table", List.of(columns), List.of(), List.of());
    }

    private static SnapshotEntity entity(String name, List<SnapshotColumn> columns,
                                         List<String> keyColumns,
                                         List<SnapshotForeignKey> foreignKeys) {
        return entity(name, "table", columns, keyColumns, foreignKeys);
    }

    private static SnapshotEntity entity(String name, String kind, List<SnapshotColumn> columns,
                                         List<String> keyColumns,
                                         List<SnapshotForeignKey> foreignKeys) {
        return new SnapshotEntity(name, kind, columns, keyColumns, foreignKeys);
    }

    private static SnapshotColumn column(String name, String type, boolean nullable) {
        return new SnapshotColumn(name, type, ColumnCategory.fromTypeName(type), nullable);
    }
}
