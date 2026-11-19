package com.example.dynamicmeta.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.example.dynamicmeta.connection.DatabaseType;

/**
 * Pure SQL builder for the read-only data API. It has no database access:
 * callers hand it an {@link EntityModel} and a {@link QuerySpec} and receive
 * a {@link SqlStatement} whose identifiers come from the entity and whose
 * values are always bind parameters.
 * <p>
 * Only SELECT and SELECT COUNT(*) are produced. The builder never generates
 * INSERT, UPDATE, DELETE or DDL. Column names in the query are resolved
 * against the entity's discovered columns (ignoring case) and an unknown
 * column is rejected with {@link IllegalArgumentException}; callers validate
 * first, so this is a defensive backstop.
 * <p>
 * Identifier quoting and paging syntax follow the database type:
 * <ul>
 *   <li>PostgreSQL, Oracle, H2: {@code "quoted"} identifiers; PostgreSQL and H2
 *       page with {@code LIMIT ? OFFSET ?}</li>
 *   <li>MySQL, MariaDB: {@code `quoted`} identifiers, {@code LIMIT ? OFFSET ?}</li>
 *   <li>SQL Server: {@code [quoted]} identifiers,
 *       {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}</li>
 *   <li>Oracle: {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}</li>
 * </ul>
 * Any quote character inside an identifier is doubled.
 */
public final class SelectBuilder {

    private static final char LIKE_ESCAPE = '\\';

    private final DatabaseType databaseType;
    private final String catalog;
    private final String schema;

    /**
     * Creates a builder for one database namespace.
     *
     * @param databaseType dialect used for quoting and paging
     * @param catalog      discovered catalog, may be null; used for SQL Server
     *                     and MySQL when present
     * @param schema       discovered schema, may be null
     */
    public SelectBuilder(DatabaseType databaseType, String catalog, String schema) {
        this.databaseType = Objects.requireNonNull(databaseType, "databaseType");
        this.catalog = catalog;
        this.schema = schema;
    }

    /** Creates a builder without a catalog. */
    public SelectBuilder(DatabaseType databaseType, String schema) {
        this(databaseType, null, schema);
    }

    /** Creates a builder for an unqualified table. */
    public SelectBuilder(DatabaseType databaseType) {
        this(databaseType, null, null);
    }

    /**
     * Builds the row SELECT for the entity and query.
     *
     * @param entity discovered table or view
     * @param query  validated query description
     * @return SQL and bind parameters in placeholder order
     * @throws IllegalArgumentException when a requested column is not on the entity
     */
    public SqlStatement select(EntityModel entity, QuerySpec query) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(query, "query");

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(selectList(entity, query))
                .append(" FROM ")
                .append(qualifiedTable(entity));
        appendWhere(sql, entity, query, parameters);
        appendOrderBy(sql, entity, query);
        appendPaging(sql, query, parameters);
        return new SqlStatement(sql.toString(), parameters);
    }

    /**
     * Builds a {@code SELECT COUNT(*)} with the same WHERE clause and bind
     * parameters as {@link #select(EntityModel, QuerySpec)}. Sorting, field
     * selection and paging do not affect the count.
     *
     * @param entity discovered table or view
     * @param query  validated query description
     * @return SQL and bind parameters in placeholder order
     * @throws IllegalArgumentException when a requested column is not on the entity
     */
    public SqlStatement buildCount(EntityModel entity, QuerySpec query) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(query, "query");

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(qualifiedTable(entity));
        appendWhere(sql, entity, query, parameters);
        return new SqlStatement(sql.toString(), parameters);
    }

    private String selectList(EntityModel entity, QuerySpec query) {
        List<String> columns = query.fields().isEmpty()
                ? entity.columns().stream().map(ColumnModel::name).toList()
                : query.fields().stream().map(field -> resolveColumn(entity, field)).toList();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Entity '" + entity.name() + "' has no columns");
        }
        return columns.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    private void appendWhere(StringBuilder sql, EntityModel entity, QuerySpec query,
                             List<Object> parameters) {
        List<String> clauses = new ArrayList<>();
        for (Map.Entry<String, Object> entry : query.equals().entrySet()) {
            clauses.add(quote(resolveColumn(entity, entry.getKey())) + " = ?");
            parameters.add(entry.getValue());
        }
        for (Map.Entry<String, String> entry : query.contains().entrySet()) {
            clauses.add("LOWER(" + quote(resolveColumn(entity, entry.getKey())) + ")"
                    + " LIKE LOWER(?)" + likeEscapeClause());
            parameters.add("%" + escapeLike(entry.getValue()) + "%");
        }
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
    }

    private void appendOrderBy(StringBuilder sql, EntityModel entity, QuerySpec query) {
        QuerySpec.Sort sort = query.sort();
        if (sort != null) {
            sql.append(" ORDER BY ")
                    .append(quote(resolveColumn(entity, sort.column())))
                    .append(sort.effectiveDirection() == QuerySpec.Direction.DESC ? " DESC" : " ASC");
            return;
        }
        // SQL Server requires an ORDER BY before OFFSET/FETCH. Without a
        // caller sort there is no stable ordering to use, so a constant one
        // keeps the paging clause valid.
        if (databaseType == DatabaseType.SQLSERVER && query.hasPaging()) {
            sql.append(" ORDER BY (SELECT NULL)");
        }
    }

    private void appendPaging(StringBuilder sql, QuerySpec query, List<Object> parameters) {
        Integer limit = query.limit();
        Integer offset = query.offset();
        if (limit == null && offset == null) {
            return;
        }
        if (databaseType == DatabaseType.SQLSERVER || databaseType == DatabaseType.ORACLE) {
            if (offset != null && limit != null) {
                sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
                parameters.add(offset);
                parameters.add(limit);
            } else if (offset != null) {
                sql.append(" OFFSET ? ROWS");
                parameters.add(offset);
            } else {
                sql.append(" OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
                parameters.add(limit);
            }
            return;
        }
        if (limit != null) {
            sql.append(" LIMIT ?");
            parameters.add(limit);
        } else if (offset != null && isMySqlFamily()) {
            // MySQL and MariaDB only allow OFFSET together with LIMIT; use the
            // documented maximum BIGINT idiom when no limit was requested.
            sql.append(" LIMIT 18446744073709551615");
        }
        if (offset != null) {
            sql.append(" OFFSET ?");
            parameters.add(offset);
        }
    }

    private String qualifiedTable(EntityModel entity) {
        List<String> parts = new ArrayList<>(3);
        if (includesCatalog() && isNotBlank(catalog)) {
            parts.add(quote(catalog));
        }
        if (isNotBlank(schema)) {
            parts.add(quote(schema));
        }
        parts.add(quote(entity.name()));
        return String.join(".", parts);
    }

    private boolean includesCatalog() {
        return databaseType == DatabaseType.SQLSERVER || databaseType == DatabaseType.MYSQL;
    }

    private boolean isMySqlFamily() {
        return databaseType == DatabaseType.MYSQL || databaseType == DatabaseType.MARIADB;
    }

    private String quote(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (databaseType) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            case POSTGRESQL, ORACLE, H2 -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    /**
     * Resolves a caller-supplied column name to the discovered column name,
     * ignoring case so quoted identifiers keep the database's own spelling.
     */
    private String resolveColumn(EntityModel entity, String requested) {
        if (requested != null) {
            for (ColumnModel column : entity.columns()) {
                if (column.name().equalsIgnoreCase(requested)) {
                    return column.name();
                }
            }
        }
        throw new IllegalArgumentException(
                "Unknown column '" + requested + "' for entity '" + entity.name() + "'");
    }

    /**
     * Returns the ESCAPE clause with a dialect-appropriate literal. MySQL and
     * MariaDB treat backslash as an escape character inside string literals,
     * so the escape character itself must be doubled there.
     */
    private String likeEscapeClause() {
        if (isMySqlFamily()) {
            return " ESCAPE '\\\\'";
        }
        return " ESCAPE '\\'";
    }

    /** Escapes the LIKE wildcards and the escape character itself. */
    private static String escapeLike(String value) {
        Objects.requireNonNull(value, "contains value");
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
