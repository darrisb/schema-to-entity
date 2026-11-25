package com.example.dynamicmeta.graph;

import java.util.List;
import java.util.Objects;

/**
 * One table or view of a source inside a {@link SchemaGraph}, identified by
 * its {@link GraphIds#entityId} so relationships and overlays can reference
 * it from anywhere in the combined document.
 * <p>
 * {@code kind} uses the same discovery kind tags as
 * {@link com.example.dynamicmeta.data.EntityModel}: {@code table},
 * {@code view} or {@code materialized-view}. {@code keyColumns} is the
 * primary key for a table or the configured view key for a view, and is
 * empty when the entity has no usable key. {@code label} and
 * {@code description} are optional display fields and may be null.
 * {@code baseTables} lists the tables a view reads and is empty for tables.
 *
 * @param id          {@code source:entity}, lower-cased
 * @param source      source id the entity was discovered in
 * @param name        entity name as discovered
 * @param kind        {@code table}, {@code view} or {@code materialized-view}
 * @param label       optional display label, possibly null
 * @param description optional description, possibly null
 * @param keyColumns  key columns in key order, possibly empty
 * @param columns     discovered columns sorted by id
 * @param baseTables  base tables of a view, possibly empty
 */
public record GraphEntity(String id, String source, String name, String kind, String label,
                          String description, List<String> keyColumns, List<GraphColumn> columns,
                          List<String> baseTables) {

    public GraphEntity {
        id = Objects.requireNonNull(id, "id");
        source = Objects.requireNonNull(source, "source");
        name = Objects.requireNonNull(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        keyColumns = List.copyOf(keyColumns);
        columns = List.copyOf(columns);
        baseTables = List.copyOf(baseTables);
    }
}
