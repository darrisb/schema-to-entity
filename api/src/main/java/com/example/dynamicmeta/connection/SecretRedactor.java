package com.example.dynamicmeta.connection;

import java.util.regex.Pattern;

/** Utility for redacting database credentials from strings. */
public final class SecretRedactor {

    private static final String FALLBACK = "Database operation failed";

    /**
     * Pattern for user:pass@ in URL-style connection strings.
     * Covers postgres, postgresql, mysql, mariadb, sqlserver, mongodb, mongodb+srv.
     * <p>
     * The password group may contain {@code @} and is greedy; it stops at the
     * first path, query or fragment delimiter, so backtracking selects the last
     * {@code @} before the host. Passwords containing {@code @} are therefore
     * masked in full instead of leaving a suffix visible.
     */
    private static final Pattern URL_CREDS = Pattern.compile(
            "((?:postgres(?:ql)?|mysql|mariadb|sqlserver|mongodb(?:\\+srv)?)://[^:\\/\\s@]+:)([^/\\s?#]+)(@)");

    /**
     * Pattern for password=, pwd=, passwd= key/value pairs in query strings or
     * SQL Server-style connection strings. Matches after ?, & or ; and preserves it.
     */
    private static final Pattern KV_PASSWORD = Pattern.compile(
            "(?i)([?&;])(password|pwd|passwd)=([^&;\\s]+)");

    /**
     * Pattern for Oracle thin driver user/password@host format.
     * Oracle uses / to separate user and password (e.g., thin:user/password@host).
     */
    private static final Pattern ORACLE_CREDS = Pattern.compile(
            "(?i)(jdbc:oracle:thin:)([^/@\\s]+)/([^@]+)(@)");

    private SecretRedactor() {
    }

    /**
     * Redact any database credentials found in {@code text}.
     *
     * @param text the text that may contain secrets; may be null
     * @return the redacted text, or a generic fallback when the input is null
     */
    public static String redact(String text) {
        if (text == null) {
            return FALLBACK;
        }
        String result = ORACLE_CREDS.matcher(text).replaceAll("$1$2/***$4");
        result = URL_CREDS.matcher(result).replaceAll("$1***$3");
        result = KV_PASSWORD.matcher(result).replaceAll("$1$2=***");
        return result;
    }
}
