package com.example.dynamicmeta.data;

/**
 * Immutable description of one discovered foreign key.
 * <p>
 * {@code origin} is {@code declared} for a foreign key read from the table's
 * own JDBC metadata, and {@code inferred} when discovery copied it onto a view
 * from a base table's declared foreign key. Any discovery value other than
 * {@code inferred} is normalized to {@code declared}. {@code name} can be null
 * when the driver does not report a constraint name.
 *
 * @param name             driver-reported constraint name, possibly null
 * @param column           foreign key column on this entity
 * @param referencedTable  table the column references
 * @param referencedColumn referenced column in {@code referencedTable}
 * @param origin           {@code declared} or {@code inferred}
 */
public record ForeignKeyModel(String name, String column, String referencedTable,
                              String referencedColumn, String origin) {

    public static final String DECLARED = "declared";
    public static final String INFERRED = "inferred";

    public ForeignKeyModel {
        origin = INFERRED.equals(origin) ? INFERRED : DECLARED;
    }
}
