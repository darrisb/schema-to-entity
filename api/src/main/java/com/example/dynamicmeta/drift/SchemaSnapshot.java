package com.example.dynamicmeta.drift;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.example.dynamicmeta.data.ColumnCategory;

/**
 * A canonical, comparable snapshot of one source's schema, taken at a moment
 * in time, together with a {@code fingerprint} that changes exactly when the
 * structure changes.
 * <p>
 * The snapshot is built by {@link SnapshotBuilder} from a discovered
 * {@link com.example.dynamicmeta.data.SourceSchema}. Entities, their columns
 * and their foreign keys are sorted by lower-cased name, so two snapshots of
 * the same schema are equal no matter what order the driver reported the
 * objects in. The {@code fingerprint} is the lower-case hex SHA-256 of a
 * canonical string derived from that sorted structure; it excludes volatile
 * fields ({@code takenAt}, {@code databaseType}, the source name, labels,
 * inferred foreign keys), so it reflects structure alone.
 * <p>
 * Foreign keys recorded here are the <em>declared</em> ones only. An inferred
 * foreign key can appear or disappear without the underlying schema changing,
 * so it is left out of both the snapshot and the fingerprint; see
 * {@link SnapshotBuilder}. Hidden columns are already absent from the source
 * schema and are never added back here.
 * <p>
 * This is a value object only: nothing in it reads from or writes to any
 * source database.
 *
 * @param source       name of the source this snapshot was taken from
 * @param fingerprint  lower-case hex SHA-256 of the canonical structure
 * @param takenAt      when the snapshot was taken; never part of the fingerprint
 * @param databaseType dialect identifier, for example {@code h2} or {@code postgresql}
 * @param entities     discovered tables and views, sorted by lower-cased name
 */
public record SchemaSnapshot(String source, String fingerprint, Instant takenAt,
                             String databaseType, List<SnapshotEntity> entities) {

    public SchemaSnapshot {
        source = Objects.requireNonNull(source, "source");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        takenAt = Objects.requireNonNull(takenAt, "takenAt");
        entities = List.copyOf(entities);
    }

    /**
     * One table or view inside a {@link SchemaSnapshot}.
     *
     * @param name        entity name as discovered
     * @param kind        {@code table}, {@code view} or {@code materialized-view}
     * @param columns     discovered columns, sorted by lower-cased name
     * @param keyColumns  key columns in key order (order preserved), possibly empty
     * @param foreignKeys declared foreign keys, sorted, possibly empty; inferred ones are excluded
     */
    public record SnapshotEntity(String name, String kind, List<SnapshotColumn> columns,
                                 List<String> keyColumns, List<SnapshotForeignKey> foreignKeys) {

        public SnapshotEntity {
            name = Objects.requireNonNull(name, "name");
            kind = Objects.requireNonNull(kind, "kind");
            columns = List.copyOf(columns);
            keyColumns = List.copyOf(keyColumns);
            foreignKeys = List.copyOf(foreignKeys);
        }
    }

    /**
     * One column inside a {@link SnapshotEntity}.
     *
     * @param name     column name as discovered
     * @param type     discovered type name, including any length or scale
     * @param category coarse type category derived from {@code type}
     * @param nullable whether the column accepts NULL
     */
    public record SnapshotColumn(String name, String type, ColumnCategory category,
                                 boolean nullable) {

        public SnapshotColumn {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
            category = Objects.requireNonNull(category, "category");
        }
    }

    /**
     * One declared foreign key inside a {@link SnapshotEntity}. There is no
     * origin field because only declared foreign keys are recorded; an inferred
     * foreign key never reaches a snapshot.
     *
     * @param column           foreign key column on this entity
     * @param referencedTable  table the column references
     * @param referencedColumn referenced column in {@code referencedTable}
     */
    public record SnapshotForeignKey(String column, String referencedTable,
                                     String referencedColumn) {

        public SnapshotForeignKey {
            column = Objects.requireNonNull(column, "column");
            referencedTable = Objects.requireNonNull(referencedTable, "referencedTable");
            referencedColumn = Objects.requireNonNull(referencedColumn, "referencedColumn");
        }
    }
}
