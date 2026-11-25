package com.example.dynamicmeta.data;

import java.util.List;
import java.util.Objects;

/**
 * Immutable description of one discovered table or view.
 * <p>
 * {@code kind} is the discovery kind tag: {@code table}, {@code view} or
 * {@code materialized-view}. {@code keyColumns} is the primary key for a
 * table, or the configured view key for a view. It is empty when the object
 * has no usable key. {@code foreignKeys} are the declared foreign keys of a
 * table plus the inferred foreign keys of a view. {@code baseTables} lists the
 * tables a view reads; it is empty for tables.
 *
 * @param name        discovered object name
 * @param kind        {@code table}, {@code view} or {@code materialized-view}
 * @param columns     discovered columns in metadata order
 * @param keyColumns  key columns in key order, possibly empty
 * @param foreignKeys declared and inferred foreign keys, possibly empty
 * @param baseTables  base tables of a view in catalog order, possibly empty
 */
public record EntityModel(String name, String kind, List<ColumnModel> columns,
                          List<String> keyColumns, List<ForeignKeyModel> foreignKeys,
                          List<String> baseTables) {

    public EntityModel {
        name = Objects.requireNonNull(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        columns = List.copyOf(columns);
        keyColumns = List.copyOf(keyColumns);
        foreignKeys = List.copyOf(foreignKeys);
        baseTables = List.copyOf(baseTables);
    }

    /**
     * Constructor with the original four components, used by callers that do
     * not track foreign keys or view dependencies; both are empty.
     */
    public EntityModel(String name, String kind, List<ColumnModel> columns,
                       List<String> keyColumns) {
        this(name, kind, columns, keyColumns, List.of(), List.of());
    }

    /** Returns true for plain and materialized views. */
    public boolean isView() {
        return "view".equals(kind) || "materialized-view".equals(kind);
    }
}
