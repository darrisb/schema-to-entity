package com.example.dynamicmeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a discovered database schema into Spring dynamic-entity metadata.
 *
 * <p>The input is the shape produced by {@link SchemaDiscoveryService}, but the usual
 * alternative spellings of table, column and foreign key names are accepted as well so
 * hand-written payloads work too.
 */
public class SpringMetadataTransformer {

    private static final String DEFAULT_ENTITY_NAME_PREFIX = "Dynamic";

    private static final Set<String> TEXTUAL_TYPES =
            Set.of("varchar", "character varying", "char", "character", "bpchar", "nvarchar", "varchar2");
    private static final Set<String> EXACT_NUMERIC_TYPES = Set.of("numeric", "decimal");
    private static final Set<String> NATIVE_TYPES =
            Set.of("text", "json", "jsonb", "xml", "bpchar", "citext", "hstore");

    public Map<String, Object> transform(Map<String, Object> schema, String entityNamePrefix) {
        String prefix = entityNamePrefix == null || entityNamePrefix.isBlank()
                ? DEFAULT_ENTITY_NAME_PREFIX
                : entityNamePrefix.trim();

        List<Map<String, Object>> tables = new ArrayList<>(tables(schema));
        tables.sort(Comparator.comparing(SpringMetadataTransformer::tableName, String.CASE_INSENSITIVE_ORDER));

        Map<String, String> entityNames = new LinkedHashMap<>();
        for (Map<String, Object> table : tables) {
            String tableName = tableName(table);
            entityNames.put(tableName, prefix + pascal(singularize(tableName)));
        }

        Map<String, Map<String, Object>> entitiesByTable = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> relationshipsByTable = new LinkedHashMap<>();

        for (Map<String, Object> table : tables) {
            String tableName = tableName(table);
            List<Map<String, Object>> columns = columns(table);
            List<String> primaryKey = primaryKeyColumns(table, columns);
            List<Map<String, Object>> relationships = new ArrayList<>();

            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("entityName", entityNames.get(tableName));
            entity.put("tableName", tableName);
            entity.put("primaryKey", primaryKey(primaryKey, columns));
            entity.put("properties", properties(columns, primaryKey));
            entity.put("relationships", relationships);
            entitiesByTable.put(tableName, entity);
            relationshipsByTable.put(tableName, relationships);
        }

        for (Map<String, Object> table : tables) {
            String tableName = tableName(table);
            for (Map<String, Object> foreignKey : foreignKeys(table)) {
                String column = firstString(foreignKey, "column", "columnName", "column_name");
                String referencedTable = firstString(foreignKey, "referencedTable", "referenced_table");
                String referencedColumn =
                        firstString(foreignKey, "referencedColumn", "referenced_column", "referencedColumnName");
                Map<String, Object> referenced = entitiesByTable.get(referencedTable);
                if (column == null || referencedColumn == null || referenced == null) {
                    continue;
                }
                String relationshipName = manyToOneName(column);

                Map<String, Object> joinColumn = new LinkedHashMap<>();
                joinColumn.put("name", column);
                joinColumn.put("referencedColumnName", referencedColumn);

                Map<String, Object> owning = new LinkedHashMap<>();
                owning.put("name", relationshipName);
                owning.put("type", "many-to-one");
                owning.put("targetEntity", referenced.get("entityName"));
                owning.put("mappedBy", null);
                owning.put("joinColumns", List.of(joinColumn));

                Map<String, Object> inverse = new LinkedHashMap<>();
                inverse.put("name", collectionName(tableName));
                inverse.put("type", "one-to-many");
                inverse.put("targetEntity", entityNames.get(tableName));
                inverse.put("mappedBy", relationshipName);
                inverse.put("foreignKeyColumn", column);
                inverse.put("joinColumns", List.of());

                relationshipsByTable.get(tableName).add(owning);
                relationshipsByTable.get(referencedTable).add(inverse);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1.0");
        result.put("dialect", firstString(schema, "dialect", "source"));
        result.put("generatedAt", java.time.Instant.now().toString());
        result.put("entities", new ArrayList<>(entitiesByTable.values()));
        return result;
    }

    private Map<String, Object> primaryKey(List<String> primaryKey, List<Map<String, Object>> columns) {
        if (primaryKey.isEmpty()) {
            return Map.of("synthetic", true);
        }
        if (primaryKey.size() == 1) {
            Map<String, Object> column = column(columns, primaryKey.getFirst());
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("name", camel(primaryKey.getFirst()));
            key.put("columnName", primaryKey.getFirst());
            key.put("type", javaType(column));
            key.put("generator", generator(column));
            appendTypeDetails(key, column);
            return key;
        }
        String name = new StringBuilder()
                .append(camel(primaryKey.getFirst()))
                .append(pascal(primaryKey.get(1)))
                .toString();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("name", name);
        key.put("composite", true);
        key.put("generator", "assigned");
        List<Map<String, Object>> members = new ArrayList<>();
        for (String columnName : primaryKey) {
            Map<String, Object> column = column(columns, columnName);
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("name", camel(columnName));
            member.put("columnName", columnName);
            member.put("type", javaType(column));
            member.put("nullable", false);
            members.add(member);
        }
        key.put("columns", members);
        return key;
    }

    private List<Map<String, Object>> properties(List<Map<String, Object>> columns, List<String> primaryKey) {
        List<Map<String, Object>> properties = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = firstString(column, "name", "columnName", "column_name");
            if (name == null || primaryKey.contains(name)) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("name", camel(name));
            property.put("columnName", name);
            property.put("type", javaType(column));
            property.put("nullable", !isFalse(firstBoolean(column, "nullable", "isNullable")));
            appendTypeDetails(property, column);
            properties.add(property);
        }
        return properties;
    }

    private void appendTypeDetails(Map<String, Object> target, Map<String, Object> column) {
        ColumnType type = columnType(column);
        if (EXACT_NUMERIC_TYPES.contains(type.base())) {
            if (type.first() >= 0) {
                target.put("precision", type.first());
            }
            if (type.second() >= 0) {
                target.put("scale", type.second());
            }
        } else if (TEXTUAL_TYPES.contains(type.base()) && type.first() >= 0) {
            target.put("length", type.first());
        }
        if (NATIVE_TYPES.contains(type.base())) {
            target.put("columnSqlType", type.base());
        }
    }

    private String javaType(Map<String, Object> column) {
        return switch (columnType(column).base()) {
            case "int8", "bigint", "bigserial" -> "java.lang.Long";
            case "int4", "integer", "int", "serial" -> "java.lang.Integer";
            case "int2", "smallint", "smallserial" -> "java.lang.Short";
            case "bool", "boolean" -> "java.lang.Boolean";
            case "numeric", "decimal" -> "java.math.BigDecimal";
            case "real", "float4" -> "java.lang.Float";
            case "float8", "double precision" -> "java.lang.Double";
            case "uuid" -> "java.util.UUID";
            case "bytea" -> "byte[]";
            case "date", "time", "timetz", "time with time zone", "time without time zone",
                 "timestamp", "timestamptz", "timestamp with time zone",
                 "timestamp without time zone" -> "java.util.Date";
            default -> "java.lang.String";
        };
    }

    private String generator(Map<String, Object> column) {
        return isTrue(firstBoolean(column, "autoIncrement", "isAutoIncrement", "identity")) ? "identity" : "assigned";
    }

    private ColumnType columnType(Map<String, Object> column) {
        String raw = firstString(column, "type", "data_type", "dataType");
        if (raw == null) {
            return new ColumnType("text", -1, -1);
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int open = value.indexOf('(');
        int close = value.lastIndexOf(')');
        if (open < 0 || close < open) {
            return new ColumnType(value, -1, -1);
        }
        String[] parts = value.substring(open + 1, close).split(",");
        return new ColumnType(value.substring(0, open).trim(), parse(parts[0]),
                parts.length > 1 ? parse(parts[1]) : -1);
    }

    private List<String> primaryKeyColumns(Map<String, Object> table, List<Map<String, Object>> columns) {
        Object declared = table.get("primaryKey");
        if (declared == null) {
            declared = table.get("primary_key");
        }
        List<String> names = new ArrayList<>();
        if (declared instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    names.add(value.toString());
                }
            }
        } else if (declared != null) {
            names.add(declared.toString());
        }
        if (!names.isEmpty()) {
            return names;
        }
        for (Map<String, Object> column : columns) {
            if (isTrue(firstBoolean(column, "primaryKey", "primary_key", "isPrimaryKey"))) {
                names.add(firstString(column, "name", "columnName", "column_name"));
            }
        }
        return names;
    }

    private static String tableName(Map<String, Object> table) {
        return firstString(table, "name", "tableName", "table_name");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Map<String, Object> schema) {
        if (schema == null) {
            return List.of();
        }
        Object value = schema.get("tables");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> columns(Map<String, Object> table) {
        Object value = firstValue(table, "columns", "fields");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> foreignKeys(Map<String, Object> table) {
        Object value = firstValue(table, "foreignKeys", "foreign_keys");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream()
                .filter(column -> name.equals(firstString(column, "name", "columnName", "column_name")))
                .findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    private static Object firstValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private static String firstString(Map<String, Object> source, String... keys) {
        Object value = firstValue(source, keys);
        return value == null ? null : value.toString();
    }

    private static Boolean firstBoolean(Map<String, Object> source, String... keys) {
        Object value = firstValue(source, keys);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.valueOf(text);
        }
        return null;
    }

    private static boolean isTrue(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static boolean isFalse(Boolean value) {
        return Boolean.FALSE.equals(value);
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String manyToOneName(String column) {
        String name = column.toLowerCase(Locale.ROOT).endsWith("_id")
                ? column.substring(0, column.length() - 3)
                : column;
        return camel(singularize(name));
    }

    private static String collectionName(String referencedTable) {
        return referencedTable.toLowerCase(Locale.ROOT).endsWith("s")
                ? referencedTable
                : pluralize(referencedTable);
    }

    static String singularize(String identifier) {
        String[] parts = identifier.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append('_');
            }
            result.append(singular(parts[i]));
        }
        return result.toString();
    }

    private static String singular(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.length() > 3 && lower.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (lower.length() > 3 && (lower.endsWith("ches") || lower.endsWith("shes")
                || lower.endsWith("sses") || lower.endsWith("xes") || lower.endsWith("zes"))) {
            return word.substring(0, word.length() - 2);
        }
        if (lower.length() > 2 && lower.endsWith("s") && !lower.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String pluralize(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z")
                || lower.endsWith("ch") || lower.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    static String camel(String identifier) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : identifier.toCharArray()) {
            if (character == '_' || character == '-' || character == ' ') {
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(character));
                upper = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    static String pascal(String identifier) {
        String value = camel(identifier);
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record ColumnType(String base, int first, int second) {
    }
}
