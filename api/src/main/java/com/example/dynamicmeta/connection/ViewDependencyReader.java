package com.example.dynamicmeta.connection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads which base tables each view reads from the database catalog.
 * <p>
 * PostgreSQL, SQL Server and MySQL expose {@code information_schema.view_table_usage};
 * Oracle exposes the same information through {@code ALL_DEPENDENCIES}. Databases
 * without such a catalog, and calls that fail because a view is missing or the user
 * lacks privilege, yield an empty map so schema discovery never fails.
 */
public class ViewDependencyReader {

    private static final String VIEW_TABLE_USAGE_SQL =
            "SELECT view_name, table_name FROM information_schema.view_table_usage " +
            "WHERE view_catalog = ? AND view_schema = ?";
    /**
     * PostgreSQL's information_schema.view_table_usage only lists tables owned by the
     * current role, so a read-only account that does not own the tables gets nothing.
     * The system catalogs show dependencies to any role that can see the view, and they
     * also cover materialized views, which information_schema omits.
     */
    private static final String POSTGRES_DEPENDENCIES_SQL =
            "SELECT DISTINCT v.relname AS view_name, t.relname AS table_name " +
            "FROM pg_catalog.pg_depend d " +
            "JOIN pg_catalog.pg_rewrite r ON r.oid = d.objid " +
            "JOIN pg_catalog.pg_class v ON v.oid = r.ev_class " +
            "JOIN pg_catalog.pg_namespace vn ON vn.oid = v.relnamespace " +
            "JOIN pg_catalog.pg_class t ON t.oid = d.refobjid " +
            "WHERE d.classid = 'pg_catalog.pg_rewrite'::regclass " +
            "AND d.refclassid = 'pg_catalog.pg_class'::regclass " +
            "AND v.relkind IN ('v', 'm') AND t.relkind IN ('r', 'p') " +
            "AND v.oid <> t.oid AND vn.nspname = ?";
    private static final String MYSQL_VIEW_TABLE_USAGE_SQL =
            "SELECT view_name, table_name FROM information_schema.view_table_usage " +
            "WHERE view_schema = ?";
    private static final String ORACLE_DEPENDENCIES_SQL =
            "SELECT name, referenced_name FROM ALL_DEPENDENCIES " +
            "WHERE owner = ? AND referenced_owner = owner " +
            "AND TYPE = 'VIEW' AND REFERENCED_TYPE = 'TABLE'";

    /**
     * Reads view dependencies for one catalog/schema.
     *
     * @param databaseType the database type; null or a database without a view catalog yields an empty map
     * @param connection   an open JDBC connection
     * @param catalog      the catalog (database) name, or null where the database has none
     * @param schema       the schema name, or null where the database has none
     * @return view name to the base table names it reads, in catalog order; empty when unavailable
     */
    public Map<String, List<String>> read(DatabaseType databaseType, Connection connection,
                                          String catalog, String schema) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        if (databaseType == null || connection == null) {
            return dependencies;
        }
        String sql = sqlFor(databaseType);
        if (sql == null) {
            return dependencies;
        }

        String[] labels = labelsFor(databaseType);
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, databaseType, catalog, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String view = rs.getString(labels[0]);
                    String table = rs.getString(labels[1]);
                    if (view == null || table == null) {
                        continue;
                    }
                    grouped.computeIfAbsent(view, key -> new LinkedHashSet<>()).add(table);
                }
            }
        } catch (SQLException ignored) {
            return new LinkedHashMap<>();
        }

        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            dependencies.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return dependencies;
    }

    /** Returns the result-set column labels for the database's catalog query. */
    private static String[] labelsFor(DatabaseType databaseType) {
        if (databaseType == DatabaseType.ORACLE) {
            return new String[]{"name", "referenced_name"};
        }
        return new String[]{"view_name", "table_name"};
    }

    /** Returns the catalog query for the database type, or null when it has no view catalog. */
    private static String sqlFor(DatabaseType databaseType) {
        return switch (databaseType) {
            case POSTGRESQL -> POSTGRES_DEPENDENCIES_SQL;
            case SQLSERVER -> VIEW_TABLE_USAGE_SQL;
            case MYSQL -> MYSQL_VIEW_TABLE_USAGE_SQL;
            case ORACLE -> ORACLE_DEPENDENCIES_SQL;
            case MARIADB, H2 -> null;
        };
    }

    /** Binds the catalog/schema arguments; MySQL keeps its schema in the catalog slot. */
    private static void bind(PreparedStatement statement, DatabaseType databaseType,
                             String catalog, String schema) throws SQLException {
        switch (databaseType) {
            case POSTGRESQL -> statement.setString(1, schema);
            case SQLSERVER -> {
                statement.setString(1, catalog);
                statement.setString(2, schema);
            }
            case MYSQL -> statement.setString(1, catalog != null ? catalog : schema);
            case ORACLE -> statement.setString(1, schema);
            default -> {
                // No catalog query for this type.
            }
        }
    }
}
