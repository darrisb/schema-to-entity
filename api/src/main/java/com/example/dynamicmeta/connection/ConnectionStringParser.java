package com.example.dynamicmeta.connection;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Parses a connection string (JDBC URL or URI form) into a normalised
 * {@link ParsedConnection} containing the database type, canonical JDBC URL,
 * host, port, database name, credentials and parameters. Credentials may come
 * from the URI userinfo or from {@code user}/{@code password} URL parameters;
 * they are always passed to the driver as properties, never embedded in the
 * rebuilt JDBC URL.
 * <p>
 * JDBC URLs are parsed strictly: only the documented simple forms are
 * accepted and anything that could hide connection parameters from
 * {@link ConnectionPolicy} (multi-host lists, sub-protocols, TNS
 * descriptors, unparsed query strings, ...) is rejected with an
 * {@link IllegalArgumentException}. Error messages never include the
 * supplied connection string because it may contain a password.
 */
public class ConnectionStringParser {

    private static final Pattern HOSTNAME_PATTERN =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern PARAMETER_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern INSTANCE_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern IPV6_LITERAL_PATTERN =
            Pattern.compile("[0-9A-Fa-f:.]+");

    /** Characters that must never appear in an authority (host[:port]). */
    private static final String FORBIDDEN_AUTHORITY_CHARS = "(),=?@ \t\r\n";

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

    /**
     * Generic parser for postgresql, mysql, mariadb:
     * {@code jdbc:TYPE://host[:port][/database][?key=value&...]}.
     * The {@code //} is mandatory and a query string is only accepted after
     * a database path so that parameters can never hide inside the authority.
     */
    private ParsedConnection parseJdbcGeneric(DatabaseType type, String fullUrl, String prefix) {
        String after = fullUrl.substring(prefix.length());
        if (!after.startsWith("//")) {
            throw new IllegalArgumentException(
                    "Invalid connection string: expected '//' after the JDBC prefix");
        }
        String content = after.substring(2);

        int endAuthority = content.indexOf('/');
        String authority = endAuthority >= 0 ? content.substring(0, endAuthority) : content;
        String rest = endAuthority >= 0 ? content.substring(endAuthority) : "";

        HostPort hp = parseAuthority(authority);

        Map<String, String> params = new LinkedHashMap<>();
        String database = parseGenericPathAndQuery(rest, params);
        Credentials credentials = extractCredentials(params);
        String jdbcUrl = ParsedConnection.canonicalJdbcUrl(
                type, hp.host(), hp.port(), database, params);

        return new ParsedConnection(type, jdbcUrl, hp.host(), hp.port(), database,
                credentials.username(), credentials.password(), params);
    }

    /**
     * Parse the {@code /database[?query]} part of a generic JDBC URL.
     * A query string is only allowed when a database path precedes it.
     */
    private static String parseGenericPathAndQuery(String rest, Map<String, String> params) {
        if (rest.isEmpty()) {
            return null;
        }
        if (rest.charAt(0) == '?') {
            throw new IllegalArgumentException(
                    "Invalid connection string: query parameters require a database path");
        }

        String afterSlash = rest.substring(1);
        int query = afterSlash.indexOf('?');
        String database = query >= 0 ? afterSlash.substring(0, query) : afterSlash;
        if (query >= 0) {
            parseQueryParams(afterSlash.substring(query + 1), params);
        }
        return database.isEmpty() ? null : database;
    }

    /**
     * sqlserver: {@code jdbc:sqlserver://host[\instance][:port][;key=value...]}.
     */
    private ParsedConnection parseJdbcSqlServer(String fullUrl) {
        String content = fullUrl.substring("jdbc:sqlserver:".length());
        if (!content.startsWith("//")) {
            throw new IllegalArgumentException(
                    "Invalid connection string: expected '//' after the JDBC prefix");
        }
        content = content.substring(2);

        int endAuthority = content.indexOf(';');
        String authority = endAuthority >= 0 ? content.substring(0, endAuthority) : content;
        String rest = endAuthority >= 0 ? content.substring(endAuthority) : "";

        String host;
        Integer port;
        String instance = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException(
                        "Invalid connection string: malformed IPv6 host");
            }
            host = authority.substring(0, close + 1);
            String remainder = authority.substring(close + 1);
            if (remainder.startsWith("\\")) {
                remainder = remainder.substring(1);
            }
            InstancePort ip = parseSqlServerRemainder(remainder);
            instance = ip.instance();
            port = ip.port();
        } else {
            int backslash = authority.indexOf('\\');
            if (backslash >= 0) {
                host = authority.substring(0, backslash);
                InstancePort ip = parseSqlServerRemainder(authority.substring(backslash + 1));
                instance = ip.instance();
                port = ip.port();
            } else {
                HostPort hp = parseAuthority(authority);
                host = hp.host();
                port = hp.port();
            }
        }
        if (!isValidHost(host)) {
            throw new IllegalArgumentException("Invalid connection string: invalid host");
        }

        Map<String, String> params = new LinkedHashMap<>();
        String database = null;
        if (!rest.isEmpty()) {
            ParsedResult pr = parseSemicolonParams(rest.substring(1), params, k -> null, true);
            if (pr.database() != null) database = pr.database();
        }
        // Preserve a named instance as a regular, allowlisted parameter so the
        // canonical URL still targets the same instance.
        if (instance != null && !containsKeyIgnoreCase(params, "instanceName")) {
            Map<String, String> withInstance = new LinkedHashMap<>();
            withInstance.put("instanceName", instance);
            withInstance.putAll(params);
            params = withInstance;
        }
        Credentials credentials = extractCredentials(params);
        String jdbcUrl = ParsedConnection.canonicalJdbcUrl(
                DatabaseType.SQLSERVER, host, port, database, params);

        return new ParsedConnection(DatabaseType.SQLSERVER, jdbcUrl, host, port, database,
                credentials.username(), credentials.password(), params);
    }

    /**
     * Parse the part after an SQL Server host or IPv6 literal:
     * empty, {@code :port}, {@code \instance} or {@code \instance:port}.
     */
    private static InstancePort parseSqlServerRemainder(String remainder) {
        if (remainder.isEmpty()) {
            return new InstancePort(null, null);
        }
        if (remainder.charAt(0) == ':') {
            return new InstancePort(null, parsePort(remainder.substring(1)));
        }
        int colon = remainder.indexOf(':');
        String instance = colon >= 0 ? remainder.substring(0, colon) : remainder;
        if (instance.isEmpty() || !INSTANCE_PATTERN.matcher(instance).matches()) {
            throw new IllegalArgumentException(
                    "Invalid connection string: invalid instance name");
        }
        return new InstancePort(instance,
                colon < 0 ? null : parsePort(remainder.substring(colon + 1)));
    }

    /**
     * oracle: only {@code @//host:port/service} or {@code @host:port:sid}.
     * TNS descriptors, aliases and query strings are rejected.
     */
    private ParsedConnection parseJdbcOracle(String fullUrl) {
        String after = fullUrl.substring("jdbc:oracle:thin:".length());
        if (!after.startsWith("@")) {
            throw new IllegalArgumentException(
                    "Invalid Oracle connection string: expected " +
                    "'@//host:port/service' or '@host:port:sid'");
        }
        String connect = after.substring(1);
        if (connect.isEmpty()) {
            throw new IllegalArgumentException("Invalid Oracle connection string: missing host");
        }
        if (connect.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid Oracle connection string: query parameters are not allowed");
        }

        String host;
        Integer port;
        String database;

        if (connect.startsWith("//")) {
            String rest = connect.substring(2);
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                throw new IllegalArgumentException(
                        "Invalid Oracle connection string: expected '@//host:port/service'");
            }
            HostPort hp = parseAuthority(rest.substring(0, slash));
            if (hp.port() == null) {
                throw new IllegalArgumentException(
                        "Invalid Oracle connection string: port is required");
            }
            host = hp.host();
            port = hp.port();
            database = rest.substring(slash);
            if (database.length() <= 1) {
                throw new IllegalArgumentException(
                        "Invalid Oracle connection string: service name is required");
            }
            rejectForbiddenChars(database.substring(1), "service name");
        } else {
            String hostPart;
            String afterHost;
            if (connect.startsWith("[")) {
                int close = connect.indexOf(']');
                if (close < 0) {
                    throw new IllegalArgumentException(
                            "Invalid Oracle connection string: malformed IPv6 host");
                }
                hostPart = connect.substring(0, close + 1);
                afterHost = connect.substring(close + 1);
                if (!afterHost.startsWith(":")) {
                    throw new IllegalArgumentException(
                            "Invalid Oracle connection string: expected '@host:port:sid'");
                }
                afterHost = afterHost.substring(1);
            } else {
                int firstColon = connect.indexOf(':');
                if (firstColon <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid Oracle connection string: expected '@host:port:sid'");
                }
                hostPart = connect.substring(0, firstColon);
                afterHost = connect.substring(firstColon + 1);
            }
            if (!isValidHost(hostPart)) {
                throw new IllegalArgumentException(
                        "Invalid Oracle connection string: invalid host");
            }
            int secondColon = afterHost.indexOf(':');
            if (secondColon <= 0) {
                throw new IllegalArgumentException(
                        "Invalid Oracle connection string: expected '@host:port:sid'");
            }
            port = parsePort(afterHost.substring(0, secondColon));
            database = afterHost.substring(secondColon + 1);
            if (database.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid Oracle connection string: SID is required");
            }
            host = hostPart;
        }

        String jdbcUrl = ParsedConnection.canonicalJdbcUrl(
                DatabaseType.ORACLE, host, port, database, new LinkedHashMap<>());
        return new ParsedConnection(DatabaseType.ORACLE, jdbcUrl, host, port, database,
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
                parseSemicolonParams(paramsStr, params, k -> null, false);
            } else {
                database = rest;
            }
        }

        Credentials credentials = extractCredentials(params);
        String jdbcUrl = buildH2JdbcUrl(after, host, port, database, params);
        return new ParsedConnection(DatabaseType.H2, jdbcUrl, host, port, database,
                credentials.username(), credentials.password(), params);
    }

    /**
     * Build the canonical H2 URL, preserving the original sub-protocol
     * ({@code mem:}, {@code file:}, {@code tcp://}, {@code http://},
     * {@code https://}) because it is not derivable from the parsed
     * host/database components.
     */
    private static String buildH2JdbcUrl(String after, String host, Integer port,
                                         String database, Map<String, String> params) {
        StringBuilder sb = new StringBuilder("jdbc:h2:");
        if (after.startsWith("tcp://") || after.startsWith("http://")
                || after.startsWith("https://")) {
            sb.append(after, 0, after.indexOf(':') + 3);
            if (host != null) sb.append(host);
            if (port != null) sb.append(':').append(port);
            if (database != null) sb.append(database);
        } else {
            int colon = after.indexOf(':');
            if (colon > 0) {
                sb.append(after, 0, colon + 1);
            }
            if (database != null) sb.append(database);
        }
        ParsedConnection.appendParameters(sb, params, ';', ';');
        return sb.toString();
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
            throw new IllegalArgumentException(
                    "Invalid connection string: malformed URI", e);
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

        String userInfoUser = null;
        String userInfoPassword = null;
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null) {
            int sep = rawUserInfo.indexOf(':');
            if (sep > 0) {
                userInfoUser = decode(rawUserInfo.substring(0, sep));
                userInfoPassword = decode(rawUserInfo.substring(sep + 1));
            } else {
                userInfoUser = decode(rawUserInfo);
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            parseQueryParams(rawQuery, params);
        }

        Credentials parameterCredentials = extractCredentials(params);
        String username = parameterCredentials.username() != null
                ? parameterCredentials.username() : userInfoUser;
        String password = parameterCredentials.password() != null
                ? parameterCredentials.password() : userInfoPassword;

        String jdbcUrl = ParsedConnection.canonicalJdbcUrl(type, host, port, database, params);
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

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Decode a URL-encoded value, treating {@code '+'} as a literal plus.
     * JDBC URL parameter values are not form-encoded, so {@code +} never
     * means space. Malformed {@code %} escapes are left untouched instead of
     * failing the parse (generated passwords commonly contain {@code %}).
     */
    private static String decode(String value) {
        String plusEscaped = value.replace("+", "%2B");
        byte[] decoded = percentDecode(plusEscaped);
        return decoded == null ? value : new String(decoded, StandardCharsets.UTF_8);
    }

    /**
     * Percent-decode {@code value} as UTF-8, leaving malformed escapes
     * literal. Returns {@code null} when the decoded bytes are not valid
     * UTF-8 so the caller can keep the original text.
     */
    private static byte[] percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()
                    && isHexDigit(value.charAt(i + 1)) && isHexDigit(value.charAt(i + 2))) {
                out.write((Character.digit(value.charAt(i + 1), 16) << 4)
                        | Character.digit(value.charAt(i + 2), 16));
                i += 2;
            } else {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] bytes = out.toByteArray();
        byte[] reencoded = new String(bytes, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
        return Arrays.equals(bytes, reencoded) ? bytes : null;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Remove {@code user}/{@code password} parameters (any case) from the map
     * and return them as credentials. They are passed to the driver as
     * connection properties and are never written into the JDBC URL.
     */
    private static Credentials extractCredentials(Map<String, String> params) {
        String username = null;
        String password = null;
        Iterator<Map.Entry<String, String>> it = params.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            if ("user".equalsIgnoreCase(entry.getKey())) {
                username = entry.getValue();
                it.remove();
            } else if ("password".equalsIgnoreCase(entry.getKey())) {
                password = entry.getValue();
                it.remove();
            }
        }
        return new Credentials(username, password);
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> map, String key) {
        for (String existing : map.keySet()) {
            if (existing.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** Parse ?key=value&key2=value2 into the supplied map. */
    private static void parseQueryParams(String query, Map<String, String> params) {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq > 0 ? pair.substring(0, eq) : pair;
            validateParameterKey(key);
            params.put(key, eq > 0 ? decode(pair.substring(eq + 1)) : "");
        }
    }

    /**
     * Parse ;key=value pairs from {@code rest} into {@code params}.
     * The {@code databaseExtractor} is called for each key to check whether
     * it represents the database name; if so, its value is returned.
     *
     * @param validateKeys when true, each key must be a plain parameter name
     *                     (letters, digits, '.', '_', '-'); H2 keeps its
     *                     historical permissive behaviour by passing false.
     */
    private ParsedResult parseSemicolonParams(String rest, Map<String, String> params,
                                              Function<String, String> databaseExtractor,
                                              boolean validateKeys) {
        String extractedDatabase = null;
        for (String pair : rest.split(";")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String val = decode(pair.substring(eq + 1));
                if (validateKeys) {
                    validateParameterKey(key);
                }
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

    /** Reject parameter keys that are not plain names (this also rejects '%'). */
    private static void validateParameterKey(String key) {
        if (key.isEmpty() || !PARAMETER_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Invalid connection string: invalid URL parameter name");
        }
    }

    /**
     * Parse a strict {@code host[:port]} authority for the non-H2 JDBC forms.
     * The host must be a hostname, an IPv4 literal or a bracketed IPv6 literal,
     * and the port (when present) must be in the range 1-65535.
     */
    private static HostPort parseAuthority(String authority) {
        rejectForbiddenChars(authority, "host");
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("Invalid connection string: host is required");
        }

        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException(
                        "Invalid connection string: malformed IPv6 host");
            }
            String host = authority.substring(0, close + 1);
            String remainder = authority.substring(close + 1);
            Integer port = null;
            if (!remainder.isEmpty()) {
                if (remainder.charAt(0) != ':') {
                    throw new IllegalArgumentException(
                            "Invalid connection string: malformed host");
                }
                port = parsePort(remainder.substring(1));
            }
            if (!isValidHost(host)) {
                throw new IllegalArgumentException("Invalid connection string: invalid host");
            }
            return new HostPort(host, port);
        }

        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            String host = authority.substring(0, colon);
            if (host.indexOf(':') >= 0) {
                // Unbracketed IPv6 literal; not an accepted simple form.
                throw new IllegalArgumentException("Invalid connection string: invalid host");
            }
            Integer port = parsePort(authority.substring(colon + 1));
            if (!isValidHost(host)) {
                throw new IllegalArgumentException("Invalid connection string: invalid host");
            }
            return new HostPort(host, port);
        }

        if (!isValidHost(authority)) {
            throw new IllegalArgumentException("Invalid connection string: invalid host");
        }
        return new HostPort(authority, null);
    }

    /** Parse and range-check a port. */
    private static int parsePort(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Invalid connection string: port is required");
        }
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid connection string: invalid port");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid connection string: port out of range");
        }
        return port;
    }

    /** Returns true for a hostname, IPv4 literal or bracketed IPv6 literal. */
    private static boolean isValidHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[') {
            if (!host.endsWith("]") || host.length() < 3) {
                return false;
            }
            return isValidIpv6(host.substring(1, host.length() - 1));
        }
        return HOSTNAME_PATTERN.matcher(host).matches();
    }

    private static boolean isValidIpv6(String value) {
        if (value.isEmpty() || value.indexOf(':') < 0
                || !IPV6_LITERAL_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Reject characters that must never appear in an authority or service name. */
    private static void rejectForbiddenChars(String value, String what) {
        for (int i = 0; i < value.length(); i++) {
            if (FORBIDDEN_AUTHORITY_CHARS.indexOf(value.charAt(i)) >= 0) {
                throw new IllegalArgumentException(
                        "Invalid connection string: invalid " + what);
            }
        }
    }

    /**
     * Parse a host:port string into host and port (H2 historical behaviour).
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
    }

    /** Credentials taken from user/password URL parameters. */
    private record Credentials(String username, String password) {
    }

    /** SQL Server instance name and port parsed from the authority. */
    private record InstancePort(String instance, Integer port) {
    }

    /** Simple pair of host and port. */
    private record HostPort(String host, Integer port) {
    }
}
