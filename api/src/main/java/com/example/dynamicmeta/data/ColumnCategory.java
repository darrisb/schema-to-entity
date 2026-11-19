package com.example.dynamicmeta.data;

import java.util.Locale;

/**
 * Coarse type category for a discovered column.
 * <p>
 * The category is derived from the JDBC type name reported by the driver, so
 * it is only a coarse grouping. It is used to decide how a column may be
 * compared or rendered, never to issue writes.
 */
public enum ColumnCategory {

    NUMBER,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    TEXT,
    OTHER;

    /**
     * Derives the category from a discovered type name such as
     * {@code bigint}, {@code varchar(80)}, {@code decimal(10,2)},
     * {@code timestamp} or {@code character varying}.
     * <p>
     * The match is case-insensitive and ignores any {@code (length)} or
     * {@code (precision, scale)} suffix. Unknown type names become
     * {@link #OTHER}; this method never throws.
     */
    public static ColumnCategory fromTypeName(String typeName) {
        if (typeName == null) {
            return OTHER;
        }
        String base = typeName.trim().toLowerCase(Locale.ROOT);
        int paren = base.indexOf('(');
        if (paren >= 0) {
            base = base.substring(0, paren).trim();
        }
        // MySQL/MariaDB spell some integer types "int unsigned".
        base = base.replace(" unsigned", "").replace(" zerofill", "").trim();
        if (base.isEmpty()) {
            return OTHER;
        }

        // Timestamp spellings must be checked before DATE: "datetime" contains "date".
        if (base.contains("timestamp") || base.contains("datetime")) {
            return TIMESTAMP;
        }
        if (base.equals("date")) {
            return DATE;
        }
        if (base.contains("bool")) {
            return BOOLEAN;
        }
        if (isNumber(base)) {
            return NUMBER;
        }
        if (isText(base)) {
            return TEXT;
        }
        return OTHER;
    }

    private static boolean isNumber(String base) {
        return switch (base) {
            case "int", "integer", "bigint", "smallint", "tinyint", "mediumint",
                 "int2", "int4", "int8", "serial", "bigserial", "smallserial",
                 "decimal", "dec", "numeric", "number", "real", "float", "float4",
                 "float8", "double", "double precision", "money", "smallmoney",
                 "binary float", "binary double" -> true;
            default -> false;
        };
    }

    private static boolean isText(String base) {
        return base.contains("char") || base.contains("text") || base.contains("clob")
                || base.equals("string");
    }
}
