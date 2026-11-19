package com.example.dynamicmeta.data;

import java.util.Objects;

/**
 * Immutable description of one discovered column.
 * <p>
 * {@code type} is the type name reported by the driver, for example
 * {@code bigint} or {@code varchar(80)}. {@code category} is the coarse
 * grouping derived from that name. The data API is read-only: a column model
 * is never used to generate INSERT, UPDATE, DELETE or DDL.
 *
 * @param name     discovered column name
 * @param type     discovered type name, including any length or scale
 * @param category coarse type category derived from {@code type}
 * @param nullable whether the column accepts NULL
 */
public record ColumnModel(String name, String type, ColumnCategory category, boolean nullable) {

    public ColumnModel {
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
        category = Objects.requireNonNull(category, "category");
    }
}
