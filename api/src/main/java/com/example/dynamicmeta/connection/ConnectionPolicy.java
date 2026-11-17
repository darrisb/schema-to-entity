package com.example.dynamicmeta.connection;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates a {@link ParsedConnection} before any JDBC connection is opened.
 * <p>
 * Enforces:
 * <ul>
 *   <li>Host allowlist (exact or *.wildcard match)</li>
 *   <li>Rejection of link-local and cloud metadata addresses</li>
 *   <li>Embedded/database file access control</li>
 *   <li>JDBC URL parameter allowlist per database type</li>
 * </ul>
 */
public class ConnectionPolicy {

    private static final Set<String> ALLOWED_POSTGRESQL_PARAMS = Set.of(
            "ssl", "sslmode", "connecttimeout", "sockettimeout",
            "applicationname", "currentschema");

    private static final Set<String> ALLOWED_MYSQL_PARAMS = Set.of(
            "usessl", "sslmode", "requiressl", "connecttimeout",
            "sockettimeout", "servertimezone", "characterencoding");

    private static final Set<String> ALLOWED_SQLSERVER_PARAMS = Set.of(
            "encrypt", "trustservercertificate", "databasename",
            "instancename", "logintimeout", "applicationname");

    private static final Set<String> ALLOWED_ORACLE_PARAMS = Set.of();

    private static final Set<String> ALLOWED_H2_PARAMS = Set.of(
            "db_close_delay", "db_close_on_exit", "file_lock", "cache_size",
            "max_row_fetch", "query_timeout", "access_mode_data", "compress",
            "ifexists");

    private static final List<String> FORBIDDEN_METADATA = List.of(
            "metadata.google.internal",
            "metadata.google.internal.",
            "fd00:ec2::254");

    private final List<String> allowedHosts;
    private final boolean allowEmbeddedDatabases;

    /**
     * @param allowedHosts list of allowed host patterns; empty means any host is allowed
     *                     (subject to other policy checks). Patterns may use a leading
     *                     '*.' wildcard, e.g. "*.example.com".
     * @param allowEmbeddedDatabases whether H2 and other embedded or file-based
     *                               databases are permitted
     */
    public ConnectionPolicy(List<String> allowedHosts, boolean allowEmbeddedDatabases) {
        this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        this.allowEmbeddedDatabases = allowEmbeddedDatabases;
    }

    /**
     * Validate the parsed connection. Returns normally if acceptable,
     * or throws {@link ConnectionRejectedException} with a reason.
     */
    public void validate(ParsedConnection parsed) {
        // 1. Embedded / file database check
        if (isEmbedded(parsed)) {
            if (!allowEmbeddedDatabases) {
                throw new ConnectionRejectedException(
                        "Embedded and file-based databases are not allowed");
            }
        }

        // 2. Host allowlist and metadata-IP checks
        validateHost(parsed.host());

        // 3. URL parameter allowlist
        validateParameters(parsed.databaseType(), parsed.parameters());
    }

    // -------------------------------------------------------------------
    // Host validation
    // -------------------------------------------------------------------

    private void validateHost(String host) {
        if (host == null || host.isBlank()) {
            // No host (embedded db) – if allowed-hosts is non-empty,
            // there's nothing to match against, so reject.
            if (!allowedHosts.isEmpty()) {
                throw new ConnectionRejectedException(
                        "Host is not allowed: no host provided");
            }
            return;
        }

        // Always reject known metadata / cloud addresses, regardless of allowlist
        if (isForbiddenMetadata(host)) {
            throw new ConnectionRejectedException(
                    "Host is not allowed: " + host);
        }

        // Apply allowlist (empty means permit any host)
        if (!allowedHosts.isEmpty() && !matchesAllowlist(host)) {
            throw new ConnectionRejectedException(
                    "Host is not allowed: " + host);
        }

        // Resolve IP and reject link-local / cloud metadata IPs
        rejectForbiddenIps(host);
    }

    /** Returns true when {@code host} matches an entry in {@link #allowedHosts}. */
    private boolean matchesAllowlist(String host) {
        for (String pattern : allowedHosts) {
            if (host.equalsIgnoreCase(pattern)) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // ".example.com"
                if (host.toLowerCase(Locale.ROOT).endsWith(suffix)
                        && host.length() > pattern.length()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns true when {@code host} is a known metadata address. */
    private static boolean isForbiddenMetadata(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return FORBIDDEN_METADATA.contains(lower);
    }

    /** Reject link-local and cloud metadata IPs resolved from {@code host}. */
    private void rejectForbiddenIps(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (isLinkLocal(addr)) {
                throw new ConnectionRejectedException(
                        "Host is not allowed: " + host);
            }
            if (addr instanceof java.net.Inet6Address) {
                byte[] bytes = addr.getAddress();
                // fd00:ec2::254
                if (bytes.length == 16
                        && bytes[0] == (byte) 0xfd && bytes[1] == 0
                        && bytes[2] == (byte) 0xec && bytes[3] == (byte) 0x02) {
                    throw new ConnectionRejectedException(
                            "Host is not allowed: " + host);
                }
            }
        } catch (ConnectionRejectedException e) {
            throw e;
        } catch (UnknownHostException ignored) {
            // Host can't be resolved yet; will fail at connection time
        } catch (Exception ignored) {
            // Resolution failed; defer to connection time
        }
    }

    /** Returns true for 169.254.0.0/16. */
    private static boolean isLinkLocal(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 4
                && bytes[0] == (byte) 169
                && bytes[1] == (byte) 254;
    }

    // -------------------------------------------------------------------
    // Embedded database detection
    // -------------------------------------------------------------------

    private boolean isEmbedded(ParsedConnection parsed) {
        if (parsed.databaseType() == DatabaseType.H2) {
            // H2 without a host is mem: or file:
            return parsed.host() == null;
        }
        // Other embedded databases would have no host either
        if (parsed.host() == null) {
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------
    // URL parameter allowlist
    // -------------------------------------------------------------------

    private void validateParameters(DatabaseType type, Map<String, String> params) {
        Set<String> allowed = getAllowlist(type);
        for (String key : params.keySet()) {
            if (!allowed.contains(key.toLowerCase(Locale.ROOT))) {
                throw new ConnectionRejectedException(
                        "Forbidden URL parameter: " + key);
            }
        }
    }

    private static Set<String> getAllowlist(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL -> ALLOWED_POSTGRESQL_PARAMS;
            case MYSQL, MARIADB -> ALLOWED_MYSQL_PARAMS;
            case SQLSERVER -> ALLOWED_SQLSERVER_PARAMS;
            case ORACLE -> ALLOWED_ORACLE_PARAMS;
            case H2 -> ALLOWED_H2_PARAMS;
        };
    }
}
