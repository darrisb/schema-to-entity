package com.example.dynamicmeta.data;

import java.util.List;
import java.util.Objects;

/**
 * One read-only SQL statement and its bind parameters.
 * <p>
 * The parameters are in placeholder order and are always bound by the JDBC
 * driver; values are never concatenated into {@link #sql()}.
 *
 * @param sql        the SQL text, containing only {@code ?} placeholders
 * @param parameters bind parameters in placeholder order
 */
public record SqlStatement(String sql, List<Object> parameters) {

    public SqlStatement {
        sql = Objects.requireNonNull(sql, "sql");
        parameters = List.copyOf(parameters);
    }
}
