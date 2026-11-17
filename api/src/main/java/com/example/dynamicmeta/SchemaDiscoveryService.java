package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionBusyException;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionRejectedException;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.DatabaseType;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.connection.NamespaceResolver;
import com.example.dynamicmeta.connection.ParsedConnection;
import com.example.dynamicmeta.connection.UnsupportedDatabaseException;
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

    /** Constructor for existing tests that do not require explicit connections. */
    public SchemaDiscoveryService(DataSource dataSource,
                                  SchemaToEntityProperties properties,
                                  ConnectionPolicy policy) {
        this(dataSource, properties, policy, null);
    }

    public SchemaDiscoveryService(DataSource dataSource,
                                  SchemaToEntityProperties properties,
                                  ConnectionPolicy policy,
                                  JdbcConnectionFactory jdbcConnectionFactory) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.policy = policy;
        this.jdbcConnectionFactory = jdbcConnectionFactory;
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

    /** Opens a one-off JDBC connection without replacing the application's DataSource. */
    public Map<String, Object> discover(ConnectionRequest request) throws SQLException {
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
            return doDiscover(request);
        } finally {
            explicitConnectionSemaphore.release();
        }
    }

    private Map<String, Object> doDiscover(ConnectionRequest request) throws SQLException {
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

        Connection connection;
        if (jdbcConnectionFactory != null) {
            connection = jdbcConnectionFactory.open(parsed,
                    request.username(), request.password(), request.ssl());
        } else {
            // Fallback for tests that don't supply a factory.
            connection = openConnectionDirectly(parsed, request);
        }

        try {
            return inspect(connection, firstNonBlank(request.schemaName(), properties.getSchema()),
                    safeTables(request.tables()));
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private static Connection openConnectionDirectly(ParsedConnection parsed,
                                                    ConnectionRequest request) throws SQLException {
        Properties connectionProperties = new Properties();
        putIfPresent(connectionProperties, "user",
                firstNonBlank(request.username(), parsed.username()));
        putIfPresent(connectionProperties, "password",
                firstNonBlank(request.password(), parsed.password()));
        if (Boolean.TRUE.equals(request.ssl())) {
            connectionProperties.setProperty("sslmode", "require");
        }
        return DriverManager.getConnection(parsed.jdbcUrl(), connectionProperties);
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
        try (ResultSet rs = metadata.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!filter.isEmpty() && !filter.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                tables.add(readTable(metadata, catalog, schema, name));
            }
        }
        tables.sort(Comparator.comparing(table -> (String) table.get("name"), String.CASE_INSENSITIVE_ORDER));

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

    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) properties.setProperty(key, value);
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
                    "connectionString=" + connectionString + ", " +
                    "username=" + username + ", " +
                    "password=***" + ", " +
                    "schemaName=" + schemaName + ", " +
                    "tables=" + tables + ", " +
                    "driverClassName=" + driverClassName + ", " +
                    "ssl=" + ssl + "]";
        }
    }
}
