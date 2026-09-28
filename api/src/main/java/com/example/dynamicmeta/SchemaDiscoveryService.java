package com.example.dynamicmeta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Discovers tables through standard JDBC metadata. */
public class SchemaDiscoveryService {

    private static final String SAMPLE_RESOURCE = "/sample-schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final SchemaToEntityProperties properties;

    public SchemaDiscoveryService(DataSource dataSource, SchemaToEntityProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /** Returns a fixed schema so the API can be demonstrated without a database. */
    public Map<String, Object> sample() {
        try (InputStream stream = SchemaDiscoveryService.class.getResourceAsStream(SAMPLE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Sample schema resource is missing: " + SAMPLE_RESOURCE);
            }
            return MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Sample schema could not be read", e);
        }
    }

    /** Uses the DataSource already configured by the containing Spring Boot application. */
    public Map<String, Object> discover() throws SQLException {
        return discover(properties.getSchema(), List.of());
    }

    public Map<String, Object> discover(String schema, List<String> tables) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return inspect(connection, schema, tables);
        }
    }

    /** Opens a one-off JDBC connection without replacing the application's DataSource. */
    public Map<String, Object> discover(ConnectionRequest request) throws SQLException {
        if (!properties.isExplicitConnectionsEnabled()) {
            throw new IllegalStateException("Explicit database connections are disabled");
        }
        ResolvedConnection resolved = resolve(request);
        if (request.driverClassName() != null && !request.driverClassName().isBlank()) {
            try {
                Class.forName(request.driverClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("JDBC driver was not found: " + request.driverClassName(), e);
            }
        }
        Properties connectionProperties = new Properties();
        putIfPresent(connectionProperties, "user", firstNonBlank(request.username(), resolved.username()));
        putIfPresent(connectionProperties, "password", firstNonBlank(request.password(), resolved.password()));
        if (Boolean.TRUE.equals(request.ssl())) {
            connectionProperties.setProperty("sslmode", "require");
        }
        try (Connection connection = DriverManager.getConnection(resolved.jdbcUrl(), connectionProperties)) {
            return inspect(connection, firstNonBlank(request.schemaName(), properties.getSchema()),
                    safeTables(request.tables()));
        }
    }

    Map<String, Object> inspect(Connection connection, String requestedSchema, List<String> tableFilter)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = firstNonBlank(requestedSchema, connection.getSchema());
        Set<String> filter = new LinkedHashSet<>();
        for (String table : safeTables(tableFilter)) {
            filter.add(table.toLowerCase(Locale.ROOT));
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!filter.isEmpty() && !filter.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                tables.add(readTable(metadata, connection.getCatalog(), schema, name));
            }
        }
        tables.sort(Comparator.comparing(table -> (String) table.get("name"), String.CASE_INSENSITIVE_ORDER));

        String dialect = dialect(metadata.getDatabaseProductName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", dialect);
        result.put("dialect", dialect);
        result.put("schema", schema);
        result.put("extractedAt", Instant.now().toString());
        result.put("tables", tables);
        return result;
    }

    private Map<String, Object> readTable(DatabaseMetaData metadata, String catalog, String schema, String table)
            throws SQLException {
        Map<String, Integer> primaryKeys = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKeys.put(rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ"));
            }
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                Map<String, Object> column = new LinkedHashMap<>();
                String name = rs.getString("COLUMN_NAME");
                column.put("name", name);
                column.put("type", displayType(rs));
                column.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                column.put("primaryKey", primaryKeys.containsKey(name));
                column.put("autoIncrement", "YES".equalsIgnoreCase(safeGet(rs, "IS_AUTOINCREMENT")));
                column.put("default", rs.getString("COLUMN_DEF"));
                columns.add(column);
            }
        }

        List<Map<String, Object>> foreignKeys = new ArrayList<>();
        try (ResultSet rs = metadata.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                Map<String, Object> foreignKey = new LinkedHashMap<>();
                foreignKey.put("name", rs.getString("FK_NAME"));
                foreignKey.put("column", rs.getString("FKCOLUMN_NAME"));
                foreignKey.put("referencedTable", rs.getString("PKTABLE_NAME"));
                foreignKey.put("referencedColumn", rs.getString("PKCOLUMN_NAME"));
                foreignKeys.add(foreignKey);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", table);
        result.put("columns", columns);
        result.put("foreignKeys", foreignKeys);
        if (!primaryKeys.isEmpty()) {
            List<String> keys = primaryKeys.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .toList();
            result.put("primaryKey", keys.size() == 1 ? keys.getFirst() : keys);
        }
        return result;
    }

    private static String displayType(ResultSet rs) throws SQLException {
        String type = rs.getString("TYPE_NAME").toLowerCase(Locale.ROOT);
        int size = rs.getInt("COLUMN_SIZE");
        int scale = rs.getInt("DECIMAL_DIGITS");
        int jdbcType = rs.getInt("DATA_TYPE");
        if ((jdbcType == Types.CHAR || jdbcType == Types.VARCHAR || jdbcType == Types.NCHAR
                || jdbcType == Types.NVARCHAR) && size > 0) {
            return type + "(" + size + ")";
        }
        if ((jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC) && size > 0) {
            return type + "(" + size + "," + Math.max(scale, 0) + ")";
        }
        return type;
    }

    private static String safeGet(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String dialect(String productName) {
        String normalized = productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("postgres")) return "postgresql";
        if (normalized.contains("mariadb")) return "mariadb";
        if (normalized.contains("mysql")) return "mysql";
        if (normalized.contains("microsoft") || normalized.contains("sql server")) return "sqlserver";
        if (normalized.contains("oracle")) return "oracle";
        return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static ResolvedConnection resolve(ConnectionRequest request) {
        if (request == null || request.connectionString() == null || request.connectionString().isBlank()) {
            throw new IllegalArgumentException("A connectionString is required");
        }
        String value = request.connectionString().trim();
        if (value.startsWith("jdbc:")) {
            return new ResolvedConnection(value, null, null);
        }
        if (value.startsWith("postgres://") || value.startsWith("postgresql://")) {
            java.net.URI uri = java.net.URI.create(value);
            String user = null;
            String password = null;
            if (uri.getRawUserInfo() != null) {
                String[] parts = uri.getRawUserInfo().split(":", 2);
                user = decode(parts[0]);
                password = parts.length == 2 ? decode(parts[1]) : null;
            }
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return new ResolvedConnection(jdbcUrl, user, password);
        }
        throw new IllegalArgumentException("connectionString must be a JDBC or PostgreSQL URL");
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) properties.setProperty(key, value);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static List<String> safeTables(List<String> tables) {
        return tables == null ? List.of() : tables;
    }

    public record ConnectionRequest(
            String connectionString,
            String username,
            String password,
            String schemaName,
            List<String> tables,
            String driverClassName,
            Boolean ssl) {
    }

    private record ResolvedConnection(String jdbcUrl, String username, String password) {
    }
}
