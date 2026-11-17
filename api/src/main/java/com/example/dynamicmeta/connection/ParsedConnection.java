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

    /**
     * Builds a canonical JDBC URL from validated connection components.
     * <p>
     * The URL is assembled only from the supplied type, host, port, database
     * and parameters. Credentials are never embedded; the connection factory
     * passes them separately as {@code user}/{@code password} properties.
     * Parameter values are percent-encoded only where required by the emitted
     * URL syntax; sub-delimiters such as {@code /}, {@code ,} and {@code :}
     * are preserved so driver-visible values reach the driver unchanged. The
     * parameter order is preserved.
     * <p>
     * H2 is built by {@link ConnectionStringParser} because its sub-protocol
     * ({@code mem:}, {@code file:}, {@code tcp://}, ...) is not derivable from
     * the parsed components.
     */
    public static String canonicalJdbcUrl(DatabaseType type, String host, Integer port,
                                          String database, Map<String, String> parameters) {
        return switch (type) {
            case POSTGRESQL, MYSQL, MARIADB -> {
                StringBuilder sb = new StringBuilder("jdbc:")
                        .append(type.id()).append("://");
                appendHost(sb, host, port);
                if (database != null) {
                    sb.append('/').append(database);
                }
                appendParameters(sb, parameters, '?', '&');
                yield sb.toString();
            }
            case SQLSERVER -> {
                StringBuilder sb = new StringBuilder("jdbc:sqlserver://");
                appendHost(sb, host, port);
                if (!containsDatabaseName(parameters) && database != null) {
                    sb.append(";databaseName=").append(encodeParameterValue(database, ';'));
                }
                appendParameters(sb, parameters, ';', ';');
                yield sb.toString();
            }
            case ORACLE -> {
                StringBuilder sb = new StringBuilder("jdbc:oracle:thin:");
                if (database != null && database.charAt(0) == '/') {
                    sb.append("@//");
                    appendHost(sb, host, port);
                    sb.append(database);
                } else {
                    sb.append('@');
                    appendHost(sb, host, port);
                    sb.append(':').append(database);
                }
                yield sb.toString();
            }
            case H2 -> throw new UnsupportedOperationException(
                    "H2 JDBC URLs are built by ConnectionStringParser");
        };
    }

    private static void appendHost(StringBuilder sb, String host, Integer port) {
        if (host != null) {
            sb.append(host);
        }
        if (port != null) {
            sb.append(':').append(port);
        }
    }

    private static boolean containsDatabaseName(Map<String, String> parameters) {
        for (String key : parameters.keySet()) {
            if ("databasename".equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** Appends {@code key=value} pairs, percent-encoding each value. */
    static void appendParameters(StringBuilder sb, Map<String, String> parameters,
                                 char separator, char pairSeparator) {
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            sb.append(first ? separator : pairSeparator)
                    .append(entry.getKey())
                    .append('=')
                    .append(encodeParameterValue(entry.getValue(), pairSeparator));
            first = false;
        }
    }

    /**
     * Percent-encodes only the characters that are structurally unsafe in the
     * emitted URL syntax, leaving sub-delimiters ({@code /}, {@code ,},
     * {@code :}, ...) intact so values such as {@code currentSchema=a/b} or
     * {@code serverTimezone=Europe/Paris} reach the driver unchanged.
     * <p>
     * JDBC drivers split URL properties on the pair separator and {@code =}
     * without percent-decoding, so form-style encoding ({@code +} for space,
     * {@code %2F} for {@code /}) would corrupt driver-visible values. Only the
     * characters that could change the URL structure are escaped.
     *
     * @param pairSeparator the character separating {@code key=value} pairs:
     *                      {@code &} for {@code ?k=v&...} forms, {@code ;} for
     *                      the semicolon forms used by SQL Server and H2
     */
    static String encodeParameterValue(String value, char pairSeparator) {
        String text = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isStructurallyUnsafe(c, pairSeparator)) {
                sb.append('%').append(HEX_DIGITS[(c >> 4) & 0xF]).append(HEX_DIGITS[c & 0xF]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isStructurallyUnsafe(char c, char pairSeparator) {
        if (c == '%' || c == '=') {
            return true;
        }
        if (pairSeparator == ';') {
            return c == ';';
        }
        // '?'/'&' form: '&', '#', '?' and whitespace would change the URL
        // structure or are not valid unencoded in a URL.
        return c == '&' || c == '#' || c == '?' || c <= ' ' || c == 0x7F;
    }

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
}
