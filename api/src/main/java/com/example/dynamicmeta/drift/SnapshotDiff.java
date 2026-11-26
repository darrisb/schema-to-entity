package com.example.dynamicmeta.drift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.example.dynamicmeta.drift.SchemaChange.Severity;
import com.example.dynamicmeta.drift.SchemaChange.Type;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotColumn;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;

/**
 * Compares a baseline {@link SchemaSnapshot} with the current one and describes
 * every difference as a {@link SchemaChange} with a severity.
 * <p>
 * {@link #diff(SchemaSnapshot, SchemaSnapshot)} is a pure function over the two
 * snapshots: it never opens a connection and never writes anything, so the same
 * pair of snapshots always produces the same list. Objects are matched by
 * lower-cased name, exactly as {@link SnapshotBuilder} fingerprints them, so a
 * driver that reports {@code USERS} today and {@code users} tomorrow is the same
 * table and yields no change; a change reported inside an object that exists in
 * both snapshots is filed under the name as the baseline spelled it.
 * <p>
 * Severities follow what a reader of the data would notice. An entity, column
 * or declared foreign key that appears is {@link Severity#ADDITIVE}; one that
 * disappears is {@link Severity#BREAKING}, and so are a changed entity kind, a
 * column that becomes {@code NOT NULL}, a new {@code NOT NULL} column and a
 * changed key. A column that appears {@code nullable} is additive. An entity
 * that is gone is reported once, as {@link Type#ENTITY_REMOVED}: its columns
 * are not filed individually, because it is the table that disappeared, not
 * each of its fields.
 * <p>
 * A type change is graded by how far it moves. Across {@link com.example.dynamicmeta.data.ColumnCategory}
 * categories ({@code bigint} to {@code varchar}) it is breaking. Within one
 * category, the type name is split into its family (the part before the
 * parentheses) and its leading size or precision, so {@code varchar(80)} to
 * {@code varchar(120)} is additive — the same family, roomier — while
 * {@code varchar(120)} to {@code varchar(80)} is breaking. Anything else in the
 * same category, such as {@code int} to {@code bigint} or a scale-only move
 * like {@code decimal(10,2)} to {@code decimal(10,4)}, is
 * {@link Severity#REVIEW}: it may be harmless, but metadata alone cannot say.
 * Types are compared case- and whitespace-insensitively, so a driver that
 * spells the same type differently does not report a change at all.
 * <p>
 * Renames can only be guessed from names, so they never replace the removal and
 * the addition: they are reported as extra candidates at
 * {@link Severity#REVIEW}, pointing at the other side through
 * {@link SchemaChange#relatedTo()}. Two columns of the same entity are a
 * candidate when they have the same type and their lower-cased names either
 * contain one another or are at least {@link #NAME_SIMILARITY_THRESHOLD}
 * similar, where similarity is one minus the edit distance over the longer
 * name. Two entities, one removed and one added, are a candidate when they
 * share at least {@link #ENTITY_COLUMN_OVERLAP_THRESHOLD} of their column
 * names — the shared count over the larger of the two column sets, so an entity
 * cannot look like a rename of a much bigger one.
 * <p>
 * The result is sorted by entity, then column, then change type, with the
 * candidate fields as final tie-breakers, so it reads in one pass down a
 * screen. Identical snapshots diff to an empty list.
 */
public final class SnapshotDiff {

    /**
     * Lower-cased column names this similar (1 - edit distance / longer length)
     * are treated as a possible rename, provided their types are the same.
     */
    public static final double NAME_SIMILARITY_THRESHOLD = 0.6;

    /**
     * Fraction of column names, counted over the larger of two entities, that a
     * removed and an added entity must share to look like a rename.
     */
    public static final double ENTITY_COLUMN_OVERLAP_THRESHOLD = 0.8;

    /** Written where a list of key columns is empty. */
    private static final String NO_KEYS = "none";

    /** Compares a derived name, treating a missing one (an entity-level change) as first. */
    private static final Comparator<String> NULLS_FIRST_THEN_NAME =
            Comparator.nullsFirst(Comparator.naturalOrder());

    /** Entity, then column, then type; the candidate fields break the remaining ties. */
    private static final Comparator<SchemaChange> CHANGE_ORDER = Comparator
            .comparing((SchemaChange change) -> lower(change.entity()))
            .thenComparing(change -> change.column() == null ? null : lower(change.column()),
                    NULLS_FIRST_THEN_NAME)
            .thenComparing(change -> change.type().name())
            .thenComparing(change -> change.relatedTo() == null ? null : lower(change.relatedTo()),
                    NULLS_FIRST_THEN_NAME)
            .thenComparing(SchemaChange::before, NULLS_FIRST_THEN_NAME)
            .thenComparing(SchemaChange::after, NULLS_FIRST_THEN_NAME);

    private SnapshotDiff() {
    }

    /**
     * Describes every difference between the two snapshots.
     *
     * @param baseline the accepted snapshot to compare against
     * @param current  the snapshot taken now
     * @return the changes, sorted by entity, column and type; empty when the
     *         two snapshots hold the same structure
     */
    public static List<SchemaChange> diff(SchemaSnapshot baseline, SchemaSnapshot current) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");

        Map<String, SnapshotEntity> before = entitiesByName(baseline.entities());
        Map<String, SnapshotEntity> after = entitiesByName(current.entities());

        List<SchemaChange> changes = new ArrayList<>();
        List<SnapshotEntity> removedEntities = new ArrayList<>();
        List<SnapshotEntity> addedEntities = new ArrayList<>();

        for (SnapshotEntity oldEntity : before.values()) {
            SnapshotEntity freshEntity = after.get(lower(oldEntity.name()));
            if (freshEntity == null) {
                removedEntities.add(oldEntity);
                changes.add(new SchemaChange(Type.ENTITY_REMOVED, Severity.BREAKING,
                        oldEntity.name(), null, lower(oldEntity.kind()), null, null));
                continue;
            }
            if (!lower(oldEntity.kind()).equals(lower(freshEntity.kind()))) {
                changes.add(new SchemaChange(Type.ENTITY_KIND_CHANGED, Severity.BREAKING,
                        oldEntity.name(), null, lower(oldEntity.kind()),
                        lower(freshEntity.kind()), null));
            }
            diffColumns(oldEntity, freshEntity, changes);
            diffKeyColumns(oldEntity, freshEntity, changes);
            diffForeignKeys(oldEntity, freshEntity, changes);
        }

        for (SnapshotEntity freshEntity : after.values()) {
            if (!before.containsKey(lower(freshEntity.name()))) {
                addedEntities.add(freshEntity);
                changes.add(new SchemaChange(Type.ENTITY_ADDED, Severity.ADDITIVE,
                        freshEntity.name(), null, null, lower(freshEntity.kind()), null));
            }
        }

        addEntityRenameCandidates(removedEntities, addedEntities, changes);
        changes.sort(CHANGE_ORDER);
        return List.copyOf(changes);
    }

    /**
     * Files every column difference of one entity present in both snapshots,
     * then pairs the columns that vanished with the ones that appeared to offer
     * rename candidates on top of them.
     */
    private static void diffColumns(SnapshotEntity oldEntity, SnapshotEntity freshEntity,
                                    List<SchemaChange> changes) {
        Map<String, SnapshotColumn> before = columnsByName(oldEntity.columns());
        Map<String, SnapshotColumn> after = columnsByName(freshEntity.columns());
        List<SnapshotColumn> removed = new ArrayList<>();
        List<SnapshotColumn> added = new ArrayList<>();

        for (SnapshotColumn oldColumn : before.values()) {
            SnapshotColumn freshColumn = after.get(lower(oldColumn.name()));
            if (freshColumn == null) {
                removed.add(oldColumn);
                changes.add(new SchemaChange(Type.COLUMN_REMOVED, Severity.BREAKING,
                        oldEntity.name(), oldColumn.name(), describe(oldColumn), null, null));
                continue;
            }
            if (!sameType(oldColumn.type(), freshColumn.type())) {
                changes.add(new SchemaChange(Type.COLUMN_TYPE_CHANGED,
                        typeSeverity(oldColumn, freshColumn), oldEntity.name(), oldColumn.name(),
                        oldColumn.type(), freshColumn.type(), null));
            }
            if (oldColumn.nullable() != freshColumn.nullable()) {
                changes.add(new SchemaChange(Type.COLUMN_NULLABILITY_CHANGED,
                        freshColumn.nullable() ? Severity.ADDITIVE : Severity.BREAKING,
                        oldEntity.name(), oldColumn.name(), nullability(oldColumn),
                        nullability(freshColumn), null));
            }
        }

        for (SnapshotColumn freshColumn : after.values()) {
            if (before.containsKey(lower(freshColumn.name()))) {
                continue;
            }
            added.add(freshColumn);
            // A nullable column can be ignored by existing rows; one that
            // demands a value breaks whatever writes to the table.
            changes.add(new SchemaChange(Type.COLUMN_ADDED,
                    freshColumn.nullable() ? Severity.ADDITIVE : Severity.BREAKING,
                    oldEntity.name(), freshColumn.name(), null, describe(freshColumn), null));
        }

        for (SnapshotColumn dropped : removed) {
            for (SnapshotColumn created : added) {
                if (sameType(dropped.type(), created.type())
                        && looksLikeRename(dropped.name(), created.name())) {
                    changes.add(new SchemaChange(Type.COLUMN_RENAME_CANDIDATE, Severity.REVIEW,
                            oldEntity.name(), dropped.name(), describe(dropped),
                            describe(created), created.name()));
                }
            }
        }
    }

    /** Grades a type change: across categories breaking, roomier same family additive. */
    private static Severity typeSeverity(SnapshotColumn oldColumn, SnapshotColumn freshColumn) {
        if (oldColumn.category() != freshColumn.category()) {
            return Severity.BREAKING;
        }
        if (!typeFamily(oldColumn.type()).equals(typeFamily(freshColumn.type()))) {
            // Same category, different family: int to bigint, date to timestamp.
            return Severity.REVIEW;
        }
        List<Integer> beforeSizes = typeSizes(oldColumn.type());
        List<Integer> afterSizes = typeSizes(freshColumn.type());
        if (beforeSizes.isEmpty() || afterSizes.isEmpty()) {
            // varchar to varchar(120): one side is unbounded, so nothing grew.
            return Severity.REVIEW;
        }
        int beforeSize = beforeSizes.getFirst();
        int afterSize = afterSizes.getFirst();
        if (beforeSize == afterSize) {
            // Only a later parameter moved, such as decimal(10,2) to decimal(10,4).
            return Severity.REVIEW;
        }
        return afterSize > beforeSize ? Severity.ADDITIVE : Severity.BREAKING;
    }

    /** Reports a key change only when the key, in order, is different. */
    private static void diffKeyColumns(SnapshotEntity oldEntity, SnapshotEntity freshEntity,
                                       List<SchemaChange> changes) {
        List<String> before = lowerList(oldEntity.keyColumns());
        List<String> after = lowerList(freshEntity.keyColumns());
        if (before.equals(after)) {
            return;
        }
        changes.add(new SchemaChange(Type.KEY_COLUMNS_CHANGED, Severity.BREAKING,
                oldEntity.name(), null, describeKeys(oldEntity.keyColumns()),
                describeKeys(freshEntity.keyColumns()), null));
    }

    /**
     * Compares declared foreign keys as whole triples, so a key that keeps its
     * column but points elsewhere shows up as the old key removed and the new
     * one added, which is exactly how it reads in the source.
     */
    private static void diffForeignKeys(SnapshotEntity oldEntity, SnapshotEntity freshEntity,
                                        List<SchemaChange> changes) {
        Map<String, SnapshotForeignKey> before = new LinkedHashMap<>();
        for (SnapshotForeignKey foreignKey : oldEntity.foreignKeys()) {
            before.putIfAbsent(foreignKeyKey(foreignKey), foreignKey);
        }
        Map<String, SnapshotForeignKey> after = new LinkedHashMap<>();
        for (SnapshotForeignKey foreignKey : freshEntity.foreignKeys()) {
            after.putIfAbsent(foreignKeyKey(foreignKey), foreignKey);
        }

        for (SnapshotForeignKey dropped : before.values()) {
            if (!after.containsKey(foreignKeyKey(dropped))) {
                changes.add(new SchemaChange(Type.FOREIGN_KEY_REMOVED, Severity.BREAKING,
                        oldEntity.name(), dropped.column(), describe(dropped), null, null));
            }
        }
        for (SnapshotForeignKey created : after.values()) {
            if (!before.containsKey(foreignKeyKey(created))) {
                changes.add(new SchemaChange(Type.FOREIGN_KEY_ADDED, Severity.ADDITIVE,
                        oldEntity.name(), created.column(), null, describe(created), null));
            }
        }
    }

    /** Pairs each removed entity with the added ones it shares most columns with. */
    private static void addEntityRenameCandidates(List<SnapshotEntity> removedEntities,
                                                  List<SnapshotEntity> addedEntities,
                                                  List<SchemaChange> changes) {
        for (SnapshotEntity dropped : removedEntities) {
            for (SnapshotEntity created : addedEntities) {
                if (sharesColumnNames(dropped, created)) {
                    changes.add(new SchemaChange(Type.ENTITY_RENAME_CANDIDATE, Severity.REVIEW,
                            dropped.name(), null, lower(dropped.kind()), lower(created.kind()),
                            created.name()));
                }
            }
        }
    }

    /** True when the two entities share at least the threshold of their names. */
    private static boolean sharesColumnNames(SnapshotEntity oldEntity, SnapshotEntity freshEntity) {
        Set<String> before = new LinkedHashSet<>(lowerList(oldEntity.columns().stream()
                .map(SnapshotColumn::name).toList()));
        Set<String> after = new LinkedHashSet<>(lowerList(freshEntity.columns().stream()
                .map(SnapshotColumn::name).toList()));
        if (before.isEmpty() || after.isEmpty()) {
            // Two column-less entities share nothing meaningful; that is not a rename.
            return false;
        }
        int shared = 0;
        for (String name : before) {
            if (after.contains(name)) {
                shared++;
            }
        }
        int larger = Math.max(before.size(), after.size());
        // Integer form of shared / larger >= ENTITY_COLUMN_OVERLAP_THRESHOLD.
        return shared * 100L >= Math.round(ENTITY_COLUMN_OVERLAP_THRESHOLD * 100) * larger;
    }

    /** True when two lower-cased names contain one another or are close enough. */
    private static boolean looksLikeRename(String oldName, String freshName) {
        String before = lower(oldName);
        String after = lower(freshName);
        if (before.equals(after) || before.isEmpty() || after.isEmpty()) {
            // Equal names never reach here: they matched instead of being added
            // and removed. An empty name cannot be compared by shape.
            return false;
        }
        if (containsAsWord(before, after) || containsAsWord(after, before)) {
            return true;
        }
        int longer = Math.max(before.length(), after.length());
        double similarity = 1.0 - (double) editDistance(before, after) / longer;
        return similarity >= NAME_SIMILARITY_THRESHOLD;
    }

    /** Shortest name that may count as contained in another (see containsAsWord). */
    static final int MIN_CONTAINED_LENGTH = 3;

    /**
     * True when {@code part} appears in {@code whole} as a whole word: bounded by
     * the start or end of the name or by an underscore (email in email_address,
     * id in customer_id). Very short names and matches inside a word (a in
     * created_at, name in username) do not count; those pairs still get the
     * edit-distance check.
     */
    static boolean containsAsWord(String whole, String part) {
        if (part.length() < MIN_CONTAINED_LENGTH || part.length() >= whole.length()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = whole.indexOf(part, from);
            if (at < 0) {
                return false;
            }
            int end = at + part.length();
            boolean startsWord = at == 0 || whole.charAt(at - 1) == '_';
            boolean endsWord = end == whole.length() || whole.charAt(end) == '_';
            if (startsWord && endsWord) {
                return true;
            }
            from = at + 1;
        }
    }

    /** Standard Levenshtein distance between two lower-cased names. */
    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /** The family of a type: its lower-cased name without any size or precision. */
    private static String typeFamily(String type) {
        String normalized = normalizeType(type);
        int paren = normalized.indexOf('(');
        return (paren >= 0 ? normalized.substring(0, paren) : normalized).trim();
    }

    /** The numbers inside a type's parentheses, empty when it has none. */
    private static List<Integer> typeSizes(String type) {
        String normalized = normalizeType(type);
        int open = normalized.indexOf('(');
        int close = normalized.lastIndexOf(')');
        if (open < 0 || close < open) {
            return List.of();
        }
        List<Integer> sizes = new ArrayList<>();
        for (String part : normalized.substring(open + 1, close).split(",")) {
            try {
                sizes.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException notANumber) {
                // decimal(10, s) or a dialect-specific form: nothing is comparable.
                return List.of();
            }
        }
        return sizes;
    }

    /** Types are equal when they differ only in case or spacing. */
    private static boolean sameType(String first, String second) {
        return normalizeType(first).equals(normalizeType(second));
    }

    private static String normalizeType(String type) {
        return type.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()
                .replace(" (", "(").replace("( ", "(").replace(" )", ")").replace(" ,", ",");
    }

    /** {@code varchar(120) nullable}, the whole state of a column in one line. */
    private static String describe(SnapshotColumn column) {
        return column.type() + " " + nullability(column);
    }

    private static String nullability(SnapshotColumn column) {
        return column.nullable() ? "nullable" : "not null";
    }

    /** {@code users.id}, the target side of a declared foreign key. */
    private static String describe(SnapshotForeignKey foreignKey) {
        return foreignKey.referencedTable() + "." + foreignKey.referencedColumn();
    }

    private static String describeKeys(List<String> keyColumns) {
        return keyColumns.isEmpty() ? NO_KEYS : String.join(", ", keyColumns);
    }

    private static Map<String, SnapshotEntity> entitiesByName(List<SnapshotEntity> entities) {
        Map<String, SnapshotEntity> byName = new LinkedHashMap<>();
        for (SnapshotEntity entity : entities) {
            byName.putIfAbsent(lower(entity.name()), entity);
        }
        return byName;
    }

    private static Map<String, SnapshotColumn> columnsByName(List<SnapshotColumn> columns) {
        Map<String, SnapshotColumn> byName = new LinkedHashMap<>();
        for (SnapshotColumn column : columns) {
            byName.putIfAbsent(lower(column.name()), column);
        }
        return byName;
    }

    private static String foreignKeyKey(SnapshotForeignKey foreignKey) {
        return lower(foreignKey.column()) + "@" + lower(foreignKey.referencedTable())
                + "." + lower(foreignKey.referencedColumn());
    }

    private static List<String> lowerList(List<String> names) {
        return names.stream().map(SnapshotDiff::lower).toList();
    }

    /** Lower-cases with the root locale so identifier case never affects a match. */
    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
