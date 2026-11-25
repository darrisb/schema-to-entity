package com.example.dynamicmeta.graph;

import com.example.dynamicmeta.data.ColumnCategory;

import java.util.Objects;

/**
 * One column of an entity inside a {@link SchemaGraph}, identified by its
 * {@link GraphIds#columnId} so the same column can be referenced from
 * relationships and overlays.
 * <p>
 * {@code name}, {@code type} and {@code category} mirror
 * {@link com.example.dynamicmeta.data.ColumnModel}; {@code label} and
 * {@code description} are optional display fields and may be null.
 *
 * @param id         {@code source:entity.column}, lower-cased
 * @param name       column name as discovered
 * @param type       discovered type name, including any length or scale
 * @param category   coarse type category derived from {@code type}
 * @param nullable   whether the column accepts NULL
 * @param label      optional display label, possibly null
 * @param description optional description, possibly null
 */
public record GraphColumn(String id, String name, String type, ColumnCategory category,
                          boolean nullable, String label, String description) {

    public GraphColumn {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
        category = Objects.requireNonNull(category, "category");
    }
}
