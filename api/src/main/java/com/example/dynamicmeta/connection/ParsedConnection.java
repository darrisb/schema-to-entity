package com.example.dynamicmeta.connection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed connection details extracted from a connection string. */
public record ParsedConnection(
        DatabaseType databaseType,
        String jdbcUrl,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        Map<String, String> parameters) {

    /** Returns a summary that never exposes the password. */
    @Override
    public String toString() {
        return "ParsedConnection[" +
                "databaseType=" + databaseType + ", " +
                "jdbcUrl=" + jdbcUrl + ", " +
                "host=" + host + ", " +
                "port=" + port + ", " +
                "database=" + database + ", " +
                "username=" + username + ", " +
                "password=***" + ", " +
                "parameters=" + parameters + "]";
    }

    /** Create a ParsedConnection with an empty, mutable parameters map. */
    public static ParsedConnection of(DatabaseType type, String jdbcUrl,
                                      String host, Integer port, String database) {
        return new ParsedConnection(type, jdbcUrl, host, port, database,
                null, null, new LinkedHashMap<>());
    }
}
