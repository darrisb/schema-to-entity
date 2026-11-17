package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionBusyException;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionRejectedException;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.DatabaseType;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.connection.NamespaceResolver;
import com.example.dynamicmeta.connection.ParsedConnection;
import com.example.dynamicmeta.connection.SecretRedactor;
import com.example.dynamicmeta.connection.UnsupportedDatabaseException;
import com.example.dynamicmeta.connection.ViewDependencyReader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Discovers tables through standard JDBC metadata. */
public class SchemaDiscoveryService {

    private static final String SAMPLE_RESOURCE = "/sample-schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOO_MANY_CONNECTIONS_MSG =
            "Too many concurrent schema extractions; try again shortly";
    private static final long PERMIT_WAIT_SECONDS = 2L;

    private final DataSource dataSource;
    private final SchemaToEntityProperties properties;
    private final ConnectionPolicy policy;
    private final JdbcConnectionFactory jdbcConnectionFactory;
    private final Semaphore explicitConnectionSemaphore;

    public SchemaDiscoveryService(DataSource dataSource,
                                  SchemaToEntityProperties properties,
                                  ConnectionPolicy policy,
                                  JdbcConnectionFactory jdbcConnectionFactory) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.policy = policy;
        this.jdbcConnectionFactory = Objects.requireNonNull(jdbcConnectionFactory,
                "jdbcConnectionFactory");
        int max = Math.max(properties.getMaxConcurrentExplicitConnections(), 1);
        this.explicitConnectionSemaphore = new Semaphore(max, true);
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

    /**
     * Tests a connection without reading any table or column metadata.
     * Uses the same parser, policy, factory and concurrency limit as {@link #discover(ConnectionRequest)}.
     *
     * @return map with databaseType, productName, productVersion, driverName, driverVersion, readOnly, latencyMs
     */
    public Map<String, Object> testConnection(ConnectionRequest request) throws SQLException {
        return withExplicitConnection(request, SchemaDiscoveryService::readConnectionInfo);
    }

    /**
     * Runs the shared explicit-connection pipeline: enabled check, permit,
     * parse, policy, driver check, open through the factory, then the callback.
     * The permit is always released and the connection always closed.
     */
    private <T> T withExplicitConnection(ConnectionRequest request, ConnectionCallback<T> callback)
            throws SQLException {
        if (!properties.isExplicitConnectionsEnabled()) {
            throw new ConnectionRejectedException("Explicit database connections are disabled");
        }
        boolean acquired;
        try {
            acquired = explicitConnectionSemaphore.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionBusyException(TOO_MANY_CONNECTIONS_MSG);
        }
        if (!acquired) {
            throw new ConnectionBusyException(TOO_MANY_CONNECTIONS_MSG);
        }
        try {
            ParsedConnection parsed = new ConnectionStringParser().parse(request.connectionString());
            policy.validate(parsed);
            String expectedDriver = parsed.databaseType().getDriverClassName();
            if (request.driverClassName() != null && !request.driverClassName().isBlank()) {
                if (!expectedDriver.equals(request.driverClassName())) {
                    throw new IllegalArgumentException(
                            "Unexpected driver class name: expected " + expectedDriver +
                            " for " + parsed.databaseType().id());
                }
            }
            if (!driverAvailable(expectedDriver)) {
                throw new UnsupportedDatabaseException(
                        "JDBC driver " + expectedDriver + " is not on the classpath. " +
                        "Add the required JDBC driver as a dependency.");
            }

            long openedAtMillis = System.currentTimeMillis();
            Connection connection = jdbcConnectionFactory.open(parsed,
                    request.username(), request.password(), request.ssl());
            try {
                return callback.use(connection, parsed, openedAtMillis);
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
        } finally {
            explicitConnectionSemaphore.release();
        }
    }

    @FunctionalInterface
    private interface ConnectionCallback<T> {
        T use(Connection connection, ParsedConnection parsed, long openedAtMillis) throws SQLException;
    }

    private static Map<String, Object> readConnectionInfo(Connection connection, ParsedConnection parsed,
                                                          long openedAtMillis) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        boolean readOnly = connection.isReadOnly();
        long latencyMs = System.currentTimeMillis() - openedAtMillis;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("databaseType", parsed.databaseType().id());
        result.put("productName", meta.getDatabaseProductName());
        result.put("productVersion", meta.getDatabaseProductVersion());
        result.put("driverName", meta.getDriverName());
        result.put("driverVersion", meta.getDriverVersion());
        result.put("readOnly", readOnly);
        result.put("latencyMs", latencyMs);
        return result;
    }

    /** Opens a one-off JDBC connection without replacing the application's DataSource. */
    public Map<String, Object> discover(ConnectionRequest request) throws SQLException {
        return withExplicitConnection(request, (connection, parsed, openedAtMillis) ->
                inspect(connection,
                        firstNonBlank(request.schemaName(), properties.getSchema()),
                        safeTables(request.tables())));
    }

    Map<String, Object> inspect(Connection connection, String requestedSchema, List<String> tableFilter)
            throws SQLException {
        NamespaceResolver.validateTableFilterSize(tableFilter);

        DatabaseMetaData metadata = connection.getMetaData();
        DatabaseType dbType = NamespaceResolver.fromConnection(connection);
        NamespaceResolver.ResolvedNamespace ns = new NamespaceResolver()
                .resolve(dbType, connection, requestedSchema);

        String schema = ns.schemaPattern();
        String catalog = ns.catalog();
        Set<String> filter = new LinkedHashSet<>();
        for (String table : safeTables(tableFilter)) {
            filter.add(table.toLowerCase(Locale.ROOT));
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        List<String> requestedTypes = requestedTableTypes(driverTableTypes(metadata),
                properties.isIncludeViews());
        try (ResultSet rs = metadata.getTables(catalog, escapePattern(metadata, schema), "%",
                requestedTypes.toArray(String[]::new))) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!filter.isEmpty() && !filter.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                tables.add(readTable(metadata, catalog, schema, name,
                        kindOf(safeGet(rs, "TABLE_TYPE"))));
            }
        }
        tables.sort(Comparator.comparing(table -> (String) table.get("name"), String.CASE_INSENSITIVE_ORDER));

        if (tables.stream().anyMatch(table -> isView((String) table.get("kind")))) {
            Map<String, List<String>> viewDependencies =
                    new ViewDependencyReader().read(dbType, connection, catalog, schema);
            for (Map<String, Object> table : tables) {
                if (isView((String) table.get("kind"))) {
                    table.put("baseTables", viewDependencies.getOrDefault((String) table.get("name"), List.of()));
                }
            }
        }

        String dialect = dialect(metadata.getDatabaseProductName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", dialect);
        result.put("dialect", dialect);
        result.put("catalog", catalog);
        result.put("schema", schema);
        result.put("extractedAt", Instant.now().toString());
        result.put("tables", tables);
        return result;
    }

    private Map<String, Object> readTable(DatabaseMetaData metadata, String catalog, String schema,
                                          String table, String kind) throws SQLException {
        boolean tableObject = "table".equals(kind);

        Map<String, Integer> primaryKeys = new LinkedHashMap<>();
        if (tableObject) {
            try (ResultSet rs = metadata.getPrimaryKeys(catalog, schema, table)) {
                while (rs.next()) {
                    primaryKeys.put(rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ"));
                }
            }
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(catalog, escapePattern(metadata, schema),
                escapePattern(metadata, table), "%")) {
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
        if (tableObject) {
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
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", table);
        result.put("kind", kind);
        result.put("columns", columns);
        result.put("foreignKeys", foreignKeys);
        if (tableObject && !primaryKeys.isEmpty()) {
            List<String> keys = primaryKeys.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .toList();
            result.put("primaryKey", keys.size() == 1 ? keys.getFirst() : keys);
        }
        return result;
    }

    /**
     * Returns the table types the driver reports through {@link DatabaseMetaData#getTableTypes()},
     * normalized to upper case so driver spellings can be compared reliably.
     */
    private static Set<String> driverTableTypes(DatabaseMetaData metadata) throws SQLException {
        Set<String> types = new LinkedHashSet<>();
        try (ResultSet rs = metadata.getTableTypes()) {
            while (rs.next()) {
                String type = rs.getString("TABLE_TYPE");
                if (type != null && !type.isBlank()) {
                    types.add(type.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return types;
    }

    /**
     * Chooses the metadata table types to request: TABLE always, plus VIEW and MATERIALIZED VIEW
     * when {@code includeViews} is set. Only types the driver reports are requested, so drivers
     * that do not support a type are never asked for it.
     */
    private static List<String> requestedTableTypes(Set<String> driverTypes, boolean includeViews) {
        List<String> requested = new ArrayList<>();
        String tableType = firstMatchingType(driverTypes, "TABLE", "BASE TABLE");
        if (tableType != null) {
            requested.add(tableType);
        }
        if (includeViews) {
            String viewType = firstMatchingType(driverTypes, "VIEW");
            if (viewType != null) {
                requested.add(viewType);
            }
            String materializedType = firstMatchingType(driverTypes, "MATERIALIZED VIEW", "MATERIALIZED_VIEW");
            if (materializedType != null) {
                requested.add(materializedType);
            }
        }
        // An empty array means "all types" to JDBC, so always request at least TABLE.
        if (requested.isEmpty()) {
            requested.add("TABLE");
        }
        return requested;
    }

    private static String firstMatchingType(Set<String> driverTypes, String... candidates) {
        for (String candidate : candidates) {
            if (driverTypes.contains(candidate.toUpperCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    /** Returns true for both plain and materialized view objects. */
    private static boolean isView(String kind) {
        return "view".equals(kind) || "materialized-view".equals(kind);
    }

    /** Maps a driver TABLE_TYPE value to the kind tag recorded for the object. */
    private static String kindOf(String tableType) {
        String normalized = tableType == null ? "" : tableType.trim().toUpperCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ');
        if (normalized.contains("MATERIALIZED") && normalized.contains("VIEW")) {
            return "materialized-view";
        }
        if (normalized.contains("VIEW")) {
            return "view";
        }
        return "table";
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

    /**
     * Escapes the LIKE wildcard characters in a schema or table name for JDBC metadata lookups.
     * {@link DatabaseMetaData#getTables} and {@link DatabaseMetaData#getColumns} treat the schema
     * and table arguments as patterns where '_' and '%' are wildcards, so a table named
     * {@code user_roles} would otherwise match a table named {@code userXroles}. The driver's own
     * escape string is escaped first, then '_' and '%'. If the driver does not report an escape
     * string, the name is returned unchanged.
     */
    private static String escapePattern(DatabaseMetaData metadata, String name) throws SQLException {
        if (name == null) {
            return null;
        }
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        StringBuilder escaped = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
                escaped.append(escape);
            }
            escaped.append(c);
        }
        return escaped.toString();
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

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static List<String> safeTables(List<String> tables) {
        return tables == null ? List.of() : tables;
    }

    private static boolean driverAvailable(String driverClassName) {
        try {
            Class.forName(driverClassName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public record ConnectionRequest(
            String connectionString,
            String username,
            String password,
            String schemaName,
            List<String> tables,
            String driverClassName,
            Boolean ssl) {

        @Override
        public String toString() {
            return "ConnectionRequest[" +
                    "connectionString=" + SecretRedactor.redact(connectionString) + ", " +
                    "username=" + username + ", " +
                    "password=***" + ", " +
                    "schemaName=" + schemaName + ", " +
                    "tables=" + tables + ", " +
                    "driverClassName=" + driverClassName + ", " +
                    "ssl=" + ssl + "]";
        }
    }
}
