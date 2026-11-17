package com.example.dynamicmeta.connection;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a connection string (JDBC URL or URI form) into a normalised
 * {@link ParsedConnection} containing the database type, JDBC URL, host,
 * port, database name, credentials (from URI forms only) and parameters.
 */
public class ConnectionStringParser {

    // -------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------

    /** Parse the supplied connection string. */
    public ParsedConnection parse(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("A connectionString is required");
        }

        String value = connectionString.trim();

        if (value.startsWith("jdbc:")) {
            return parseJdbcUrl(value);
        }

        if (isMongoDb(value)) {
            throw new UnsupportedDatabaseException(
                    "MongoDB is not supported by the JDBC connector yet");
        }

        if (value.contains("://")) {
            return parseUriForm(value);
        }

        throw new UnsupportedDatabaseException(
                "Unsupported connection string format. Supported formats: " +
                "jdbc:postgresql:, jdbc:mysql:, jdbc:mariadb:, jdbc:sqlserver:, " +
                "jdbc:oracle:thin:, jdbc:h2:, postgres://, postgresql://, " +
                "mysql://, mariadb://, sqlserver://");
    }

    private static boolean isMongoDb(String value) {
        return value.startsWith("mongodb://") || value.startsWith("mongodb+srv://");
    }

    // -------------------------------------------------------------------
    // JDBC URL parsers
    // -------------------------------------------------------------------

    private ParsedConnection parseJdbcUrl(String value) {
        if (value.startsWith("jdbc:postgresql:"))
            return parseJdbcGeneric(DatabaseType.POSTGRESQL, value, "jdbc:postgresql:");
        if (value.startsWith("jdbc:mysql:"))
            return parseJdbcGeneric(DatabaseType.MYSQL, value, "jdbc:mysql:");
        if (value.startsWith("jdbc:mariadb:"))
            return parseJdbcGeneric(DatabaseType.MARIADB, value, "jdbc:mariadb:");
        if (value.startsWith("jdbc:sqlserver:"))
            return parseJdbcSqlServer(value);
        if (value.startsWith("jdbc:oracle:thin:"))
            return parseJdbcOracle(value);
        if (value.startsWith("jdbc:h2:"))
            return parseJdbcH2(value);

        throw new UnsupportedDatabaseException(
                "Unsupported JDBC URL prefix. Supported prefixes: jdbc:postgresql:, " +
                "jdbc:mysql:, jdbc:mariadb:, jdbc:sqlserver:, jdbc:oracle:thin:, jdbc:h2:");
    }

    /** Generic parser for postgresql, mysql, mariadb: jdbc:TYPE://host:port/db?key=val */
    private ParsedConnection parseJdbcGeneric(DatabaseType type, String fullUrl, String prefix) {
        String after = fullUrl.substring(prefix.length());
        // Strip leading //
        String content = after.startsWith("//") ? after.substring(2) : after;

        String host = null;
        Integer port = null;
        String database = null;
        Map<String, String> params = new LinkedHashMap<>();

        // Find authority end: first / or ;
        int endAuthority = firstOf(content, '/', ';');
        String authority, rest;
        if (endAuthority >= 0) {
            authority = content.substring(0, endAuthority);
            rest = content.substring(endAuthority);
        } else {
            authority = content;
            rest = "";
        }

        HostPort hp = parseHostPort(authority);
        host = hp.host();
        port = hp.port();

        // rest starts with / (path + optional ?query) or ;
        ParsedResult pr = parsePathQueryParams(rest, params);
        if (pr.database() != null) database = pr.database();

        return new ParsedConnection(type, fullUrl, host, port, database,
                null, null, params);
    }

    /** sqlserver: jdbc:sqlserver://host:port;databaseName=db;key=val */
    private ParsedConnection parseJdbcSqlServer(String fullUrl) {
        String content = fullUrl.substring("jdbc:sqlserver:".length());
        if (content.startsWith("//")) {
            content = content.substring(2);
        }

        String host = null;
        Integer port = null;
        String database = null;
        Map<String, String> params = new LinkedHashMap<>();

        int endAuthority = firstOf(content, ';', '/');
        String authority, rest;
        if (endAuthority >= 0) {
            authority = content.substring(0, endAuthority);
            rest = content.substring(endAuthority);
        } else {
            authority = content;
            rest = "";
        }

        HostPort hp = parseHostPort(authority);
        host = hp.host();
        port = hp.port();

        // Parse ;key=value pairs (skip leading ; or /)
        if (!rest.isEmpty() && (rest.charAt(0) == ';' || rest.charAt(0) == '/')) {
            rest = rest.substring(1);
        }
        ParsedResult pr = parseSemicolonParams(rest, params, k -> null);
        if (pr.database() != null) database = pr.database();

        return new ParsedConnection(DatabaseType.SQLSERVER, fullUrl, host, port, database,
                null, null, params);
    }

    /** oracle: jdbc:oracle:thin:@//host:port/service or @host:port:sid */
    private ParsedConnection parseJdbcOracle(String fullUrl) {
        String after = fullUrl.substring("jdbc:oracle:thin:".length());

        String host = null;
        Integer port = null;
        String database = null;

        if (after.startsWith("@")) {
            String connect = after.substring(1);
            if (connect.startsWith("//")) {
                // //host:port/service
                String rest = connect.substring(2);
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    HostPort hp = parseHostPort(rest.substring(0, slash));
                    host = hp.host();
                    port = hp.port();
                    database = rest.substring(slash);
                } else {
                    HostPort hp = parseHostPort(rest);
                    host = hp.host();
                    port = hp.port();
                }
            } else {
                // host:port:sid
                int firstColon = connect.indexOf(':');
                if (firstColon > 0) {
                    host = connect.substring(0, firstColon);
                    String rest = connect.substring(firstColon + 1);
                    int secondColon = rest.indexOf(':');
                    if (secondColon > 0) {
                        try { port = Integer.parseInt(rest.substring(0, secondColon)); } catch (NumberFormatException ignored) { }
                        database = rest.substring(secondColon + 1);
                    } else {
                        try { port = Integer.parseInt(rest); } catch (NumberFormatException ignored) { }
                    }
                } else {
                    host = connect;
                }
            }
        }

        return new ParsedConnection(DatabaseType.ORACLE, fullUrl, host, port, database,
                null, null, new LinkedHashMap<>());
    }

    /** h2: mem:db;params, file:path;params, tcp://host:port/path */
    private ParsedConnection parseJdbcH2(String fullUrl) {
        String after = fullUrl.substring("jdbc:h2:".length());

        String host = null;
        Integer port = null;
        String database = null;
        Map<String, String> params = new LinkedHashMap<>();

        if (after.startsWith("tcp://") || after.startsWith("http://") || after.startsWith("https://")) {
            String uri = after.substring(4); // after "tcp:" / "http" etc.
            if (uri.startsWith("//")) {
                uri = uri.substring(2);
            }
            int slash = uri.indexOf('/');
            if (slash > 0) {
                HostPort hp = parseHostPort(uri.substring(0, slash));
                host = hp.host();
                port = hp.port();
                database = uri.substring(slash);
            } else {
                HostPort hp = parseHostPort(uri);
                host = hp.host();
                port = hp.port();
            }
        } else {
            // mem:db;params  or  file:path;params
            int colon = after.indexOf(':');
            String rest = colon > 0 ? after.substring(colon + 1) : after;

            int semi = rest.indexOf(';');
            if (semi > 0) {
                database = rest.substring(0, semi);
                String paramsStr = rest.substring(semi + 1);
                parseSemicolonParams(paramsStr, params, k -> null);
            } else {
                database = rest;
            }
        }

        return new ParsedConnection(DatabaseType.H2, fullUrl, host, port, database,
                null, null, params);
    }

    // -------------------------------------------------------------------
    // URI form parsers (postgres://, mysql://, …)
    // -------------------------------------------------------------------

    private ParsedConnection parseUriForm(String value) {
        String scheme;
        try {
            java.net.URI uri = java.net.URI.create(value);
            scheme = uri.getScheme();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid connection string: " + value, e);
        }

        DatabaseType type = mapSchemeToType(scheme);
        if (type == null) {
            throw new UnsupportedDatabaseException(
                    "Unsupported scheme '" + scheme + "'. Supported: postgres, postgresql, " +
                    "mysql, mariadb, sqlserver");
        }

        java.net.URI uri = java.net.URI.create(value);
        String host = uri.getHost();
        Integer port = uri.getPort() < 0 ? null : uri.getPort();

        String database = null;
        String rawPath = uri.getRawPath();
        if (rawPath != null && rawPath.length() > 1) {
            database = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        }

        String username = null;
        String password = null;
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null) {
            int sep = rawUserInfo.indexOf(':');
            if (sep > 0) {
                username = decode(rawUserInfo.substring(0, sep));
                password = decode(rawUserInfo.substring(sep + 1));
            } else {
                username = decode(rawUserInfo);
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            parseQueryParams(rawQuery, params);
        }

        String jdbcUrl = buildJdbcUrl(type, host, port, database, rawQuery, type == DatabaseType.SQLSERVER);
        return new ParsedConnection(type, jdbcUrl, host, port, database,
                username, password, params);
    }

    private static DatabaseType mapSchemeToType(String scheme) {
        return switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
            case "postgres", "postgresql" -> DatabaseType.POSTGRESQL;
            case "mysql" -> DatabaseType.MYSQL;
            case "mariadb" -> DatabaseType.MARIADB;
            case "sqlserver" -> DatabaseType.SQLSERVER;
            default -> null;
        };
    }

    /** Build a JDBC URL from the URI components. */
    private String buildJdbcUrl(DatabaseType type, String host, Integer port,
                                String database, String rawQuery, boolean useSemicolons) {
        StringBuilder sb = new StringBuilder("jdbc:").append(type.id()).append("://");
        if (host != null) sb.append(host);
        if (port != null) sb.append(':').append(port);

        if (database != null) {
            if (useSemicolons) {
                sb.append(";databaseName=").append(database);
            } else {
                sb.append('/').append(database);
            }
        }

        if (rawQuery != null) {
            if (useSemicolons) {
                // Convert ?a=b&c=d to ;a=b;c=d
                for (String pair : rawQuery.split("&")) {
                    sb.append(';').append(pair);
                }
            } else {
                sb.append('?').append(rawQuery);
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /** Return the index of the first occurrence of either char in the string, or -1. */
    private static int firstOf(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }

    /** Decode a URL-encoded value, treating '+' as a literal (not a space). */
    private static String decode(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Parse ?key=value&key2=value2 into the supplied map. */
    private static void parseQueryParams(String query, Map<String, String> params) {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                params.put(pair, "");
            }
        }
    }

    /**
     * Parse ;key=value pairs from {@code rest} into {@code params}.
     * The {@code databaseExtractor} is called for each key to check whether
     * it represents the database name; if so, its value is returned.
     */
    private ParsedResult parseSemicolonParams(String rest, Map<String, String> params,
                                              java.util.function.Function<String, String> databaseExtractor) {
        String extractedDatabase = null;
        for (String pair : rest.split(";")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String val = pair.substring(eq + 1);
                params.put(key, val);
                String db = databaseExtractor.apply(key);
                if (db == null && "databaseName".equalsIgnoreCase(key)) {
                    extractedDatabase = val;
                } else if (db != null) {
                    extractedDatabase = db;
                }
            }
        }
        return new ParsedResult(extractedDatabase);
    }

    /**
     * Parse a /path?query string, extracting the database name from the path
     * and populating params from the query.
     */
    private ParsedResult parsePathQueryParams(String rest, Map<String, String> params) {
        if (rest.isEmpty()) {
            return ParsedResult.EMPTY;
        }

        String database = null;
        // rest starts with /
        if (rest.charAt(0) == '/') {
            String afterSlash = rest.substring(1);
            int q = afterSlash.indexOf('?');
            if (q >= 0) {
                database = afterSlash.substring(0, q);
                parseQueryParams(afterSlash.substring(q + 1), params);
            } else {
                database = afterSlash.isEmpty() ? null : afterSlash;
            }
        }

        return new ParsedResult(database);
    }

    /**
     * Parse a host:port string into host and port.
     * Returns a HostPort with whatever could be parsed.
     */
    private static HostPort parseHostPort(String authority) {
        String host = null;
        Integer port = null;

        if (authority == null || authority.isEmpty()) {
            return new HostPort(null, null);
        }

        int colon = authority.lastIndexOf(':');
        if (colon > 0) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                host = authority; // treat whole thing as host
            }
        } else {
            host = authority;
        }

        return new HostPort(host, port);
    }

    /** Result of a parse step that may extract a database name. */
    private record ParsedResult(String database) {
        static final ParsedResult EMPTY = new ParsedResult(null);

        void ifMatch(java.util.function.Consumer<String> consumer) {
            if (database != null) consumer.accept(database);
        }
    }

    /** Simple pair of host and port. */
    private record HostPort(String host, Integer port) {
        void ifMatch(java.util.function.Consumer<String> h, java.util.function.Consumer<Integer> p) {
            if (host != null) h.accept(host);
            if (port != null) p.accept(port);
        }
    }
}
