package com.example.dynamicmeta.data;

import java.util.List;
import java.util.Objects;

/**
 * Immutable description of one discovered table or view.
 * <p>
 * {@code kind} is the discovery kind tag: {@code table}, {@code view} or
 * {@code materialized-view}. {@code keyColumns} is the primary key for a
 * table, or the configured view key for a view. It is empty when the object
 * has no usable key.
 *
 * @param name       discovered object name
 * @param kind       {@code table}, {@code view} or {@code materialized-view}
 * @param columns    discovered columns in metadata order
 * @param keyColumns key columns in key order, possibly empty
 */
public record EntityModel(String name, String kind, List<ColumnModel> columns,
                          List<String> keyColumns) {

    public EntityModel {
        name = Objects.requireNonNull(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        columns = List.copyOf(columns);
        keyColumns = List.copyOf(keyColumns);
    }

    /** Returns true for plain and materialized views. */
    public boolean isView() {
        return "view".equals(kind) || "materialized-view".equals(kind);
    }
}
