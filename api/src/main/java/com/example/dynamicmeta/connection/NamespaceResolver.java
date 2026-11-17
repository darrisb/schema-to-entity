package com.example.dynamicmeta.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves the correct catalog and schema pattern for a JDBC connection
 * depending on the database type.
 * <p>
 * Different databases use catalog and schema differently:
 * <ul>
 *   <li>PostgreSQL: tables live under schema, catalog is the database</li>
 *   <li>MySQL/MariaDB: tables live under catalog (database), schema is null</li>
 *   <li>Oracle: schema = user (uppercased), catalog is null</li>
 *   <li>SQL Server: catalog = database, schema defaults to dbo</li>
 *   <li>H2/unknown: catalog = connection catalog, schema = requested or connection schema</li>
 * </ul>
 */
public class NamespaceResolver {

    private static final int MAX_SCHEMA_NAME_LENGTH = 128;
    private static final Pattern UNQUOTED_SCHEMA_PATTERN = Pattern.compile("^[A-Za-z0-9_$]+$");
    static final int MAX_TABLE_FILTER_SIZE = 500;

    /** Resolved namespace with catalog and schema pattern for JDBC metadata queries. */
    public record ResolvedNamespace(String catalog, String schemaPattern) {
    }

    /**
     * Resolves the catalog and schema pattern for the given connection and requested schema.
     *
     * @param type            the database type
     * @param connection      an open JDBC connection
     * @param requestedSchema the schema name requested by the caller, or null
     * @return the resolved catalog and schema pattern
     * @throws SQLException            if the connection metadata cannot be read
     * @throws IllegalArgumentException if the schema name is invalid
     */
    public ResolvedNamespace resolve(DatabaseType type, Connection connection, String requestedSchema)
            throws SQLException {
        validateSchemaName(type, requestedSchema);

        DatabaseType effectiveType = (type != null) ? type : DatabaseType.H2;
        return switch (effectiveType) {
            case POSTGRESQL -> resolvePostgreSQL(connection, requestedSchema);
            case MYSQL, MARIADB -> resolveMySQL(connection, requestedSchema);
            case ORACLE -> resolveOracle(connection, requestedSchema);
            case SQLSERVER -> resolveSQLServer(connection, requestedSchema);
            case H2 -> resolveFallback(connection, requestedSchema);
        };
    }

    /**
     * Derive the DatabaseType from a JDBC connection's product name.
     * Reuses the same logic as {@code SchemaDiscoveryService.dialect()}.
     */
    public static DatabaseType fromProductName(String productName) {
        String normalized = productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("postgres")) return DatabaseType.POSTGRESQL;
        if (normalized.contains("mariadb")) return DatabaseType.MARIADB;
        if (normalized.contains("mysql")) return DatabaseType.MYSQL;
        if (normalized.contains("microsoft") || normalized.contains("sql server")) return DatabaseType.SQLSERVER;
        if (normalized.contains("oracle")) return DatabaseType.ORACLE;
        if (normalized.contains("h2")) return DatabaseType.H2;
        return null;
    }

    /**
     * Derive the DatabaseType from an open JDBC connection.
     */
    public static DatabaseType fromConnection(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        return fromProductName(productName);
    }

    // -------------------------------------------------------------------
    // Per-database resolution
    // -------------------------------------------------------------------

    private static ResolvedNamespace resolvePostgreSQL(Connection connection, String requestedSchema)
            throws SQLException {
        String catalog = connection.getCatalog();
        String schema = firstNonBlank(requestedSchema, connection.getSchema(), "public");
        return new ResolvedNamespace(catalog, schema);
    }

    private static ResolvedNamespace resolveMySQL(Connection connection, String requestedSchema)
            throws SQLException {
        // MySQL/MariaDB: catalog = database, schema is null for getTables()
        String catalog = firstNonBlank(requestedSchema, connection.getCatalog());
        return new ResolvedNamespace(catalog, null);
    }

    private static ResolvedNamespace resolveOracle(Connection connection, String requestedSchema)
            throws SQLException {
        // Oracle: schema = user (uppercased), catalog is null
        String schema;
        if (requestedSchema != null && !requestedSchema.isBlank()) {
            if (isQuotedName(requestedSchema)) {
                // Quoted identifier: preserve case
                schema = requestedSchema.substring(1, requestedSchema.length() - 1);
            } else {
                // Unquoted identifier: Oracle folds to uppercase
                schema = requestedSchema.toUpperCase(Locale.ROOT);
            }
        } else {
            // Fall back to the connection user, uppercased
            schema = connection.getMetaData().getUserName();
            if (schema != null) {
                schema = schema.toUpperCase(Locale.ROOT);
            }
        }
        return new ResolvedNamespace(null, schema);
    }

    private static ResolvedNamespace resolveSQLServer(Connection connection, String requestedSchema)
            throws SQLException {
        String catalog = connection.getCatalog();
        String schema = firstNonBlank(requestedSchema, "dbo");
        return new ResolvedNamespace(catalog, schema);
    }

    private static ResolvedNamespace resolveFallback(Connection connection, String requestedSchema)
            throws SQLException {
        // H2 / unknown: use connection's catalog and schema
        String catalog = connection.getCatalog();
        String schema = firstNonBlank(requestedSchema, connection.getSchema());
        return new ResolvedNamespace(catalog, schema);
    }

    // -------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------

    /**
     * Validates a schema name. Throws {@link IllegalArgumentException} if invalid.
     *
     * @param type            the database type (Oracle allows quoted names with spaces)
     * @param requestedSchema the schema name to validate
     */
    public static void validateSchemaName(DatabaseType type, String requestedSchema) {
        if (requestedSchema == null || requestedSchema.isBlank()) {
            return;
        }

        if (requestedSchema.length() > MAX_SCHEMA_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Schema name must be at most " + MAX_SCHEMA_NAME_LENGTH + " characters; got "
                    + requestedSchema.length() + " characters");
        }

        String nameToCheck;
        if (type == DatabaseType.ORACLE && isQuotedName(requestedSchema)) {
            // Quoted Oracle names may contain spaces and other characters -- skip pattern check
            return;
        }

        if (!UNQUOTED_SCHEMA_PATTERN.matcher(requestedSchema).matches()) {
            throw new IllegalArgumentException(
                    "Schema name contains invalid characters. Only letters, digits, '_' and '$' are allowed");
        }
    }

    /**
     * Validates that the table filter does not exceed the maximum allowed size.
     *
     * @param tables the table filter list
     * @throws IllegalArgumentException if the list exceeds the maximum size
     */
    public static void validateTableFilterSize(java.util.List<String> tables) {
        if (tables != null && tables.size() > MAX_TABLE_FILTER_SIZE) {
            throw new IllegalArgumentException(
                    "Table filter must contain at most " + MAX_TABLE_FILTER_SIZE + " entries; got "
                    + tables.size());
        }
    }

    /** Returns true if the name is enclosed in double quotes (e.g. for Oracle quoted identifiers). */
    private static boolean isQuotedName(String name) {
        return name != null && name.length() >= 2
                && name.startsWith("\"") && name.endsWith("\"");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return third;
    }
}
