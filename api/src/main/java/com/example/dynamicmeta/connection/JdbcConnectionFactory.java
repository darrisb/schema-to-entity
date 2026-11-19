package com.example.dynamicmeta.connection;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Opens a JDBC {@link Connection} with timeouts, SSL mapping and a read-only
 * session flag.
 * <p>
 * Timeout and SSL behaviour are translated to each driver's property names.
 * {@link DriverManager#setLoginTimeout(int)} is never called so that no
 * JVM-global state is mutated.
 */
public class JdbcConnectionFactory {

    private final Duration connectTimeout;
    private final Duration socketTimeout;

    /**
     * @param connectTimeout maximum time (default 10s) to wait when establishing a
     *                       connection
     * @param socketTimeout  maximum idle time between two data packets (default 60s)
     */
    public JdbcConnectionFactory(Duration connectTimeout, Duration socketTimeout) {
        this.connectTimeout = connectTimeout;
        this.socketTimeout = socketTimeout;
    }

    /**
     * Opens a connection using the parsed connection details and caller overrides.
     * <p>
     * The returned connection is put into read-only mode
     * ({@link Connection#setReadOnly(boolean)}). If the driver does not support
     * read-only mode {@link SQLFeatureNotSupportedException} is swallowed silently.
     * If any other failure happens after the connection is opened, the connection
     * is closed before the failure is rethrown.
     *
     * @param parsed        the parsed connection (from {@link ConnectionStringParser})
     * @param requestUser   caller-supplied user, may override the URL's user
     * @param requestPass   caller-supplied password, may override the URL's password
     * @param ssl           whether SSL was requested (null means caller did not
     *                      specify)
     * @return an open, read-only connection
     */
    public Connection open(ParsedConnection parsed,
                           String requestUser,
                           String requestPass,
                           Boolean ssl) throws SQLException {
        Properties props = buildProperties(parsed, requestUser, requestPass, ssl);
        String url = parsed.jdbcUrl();

        // Obtain the driver and use driver.connect to avoid JVM-global
        // DriverManager.setLoginTimeout.
        Driver driver = DriverManager.getDriver(url);
        Connection conn = driver.connect(url, props);
        if (conn == null) {
            // Never include the JDBC URL: it can embed user:password@ credentials.
            throw new ConnectionException(
                    "Driver " + driver.getClass().getName() + " refused to connect to "
                            + parsed.databaseType() + " (" + parsed.databaseType().id()
                            + ") at host " + parsed.host());
        }

        try {
            conn.setReadOnly(true);
        } catch (SQLFeatureNotSupportedException ignored) {
            // Not all drivers support read-only; continue anyway.
        } catch (SQLException | RuntimeException e) {
            // The connection is already open, so any failure while configuring it
            // must not leak it. Close it, then propagate the original failure.
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return conn;
    }

    /**
     * Returns the connection properties for the given parsed connection.
     * Useful for unit tests that inspect the built properties without opening
     * a real connection, and for pooling wrappers that must apply the same
     * timeout, SSL and read-only mapping as one-off connections.
     */
    public Properties buildProperties(ParsedConnection parsed,
                               String requestUser,
                               String requestPass,
                               Boolean ssl) {
        Properties props = new Properties();

        // Username/password: request overrides parsed URL credentials.
        String user = firstNonBlank(requestUser, parsed.username());
        String pass = firstNonBlank(requestPass, parsed.password());
        if (user != null && !user.isBlank()) {
            props.setProperty("user", user);
        }
        if (pass != null && !pass.isBlank()) {
            props.setProperty("password", pass);
        }

        // Timeout properties per driver.
        addTimeoutProperties(parsed.databaseType(), props);

        // SSL properties per driver.
        addSslProperties(parsed.databaseType(), props, parsed.jdbcUrl(), ssl);

        // Read-only enforcement per driver.
        addReadOnlyProperties(parsed.databaseType(), props);

        return props;
    }

    // -------------------------------------------------------------------
    // Timeout translation
    // -------------------------------------------------------------------

    private void addTimeoutProperties(DatabaseType type, Properties props) {
        long connectMs = connectTimeout.toMillis();
        long socketMs = socketTimeout.toMillis();

        switch (type) {
            case POSTGRESQL -> {
                // Postgres: seconds, rounded up; a value of 0 means no timeout.
                props.setProperty("connectTimeout", Long.toString(toSeconds(connectMs)));
                props.setProperty("socketTimeout", Long.toString(toSeconds(socketMs)));
            }
            case MYSQL, MARIADB -> {
                // MySQL / MariaDB: milliseconds
                props.setProperty("connectTimeout", Long.toString(connectMs));
                props.setProperty("socketTimeout", Long.toString(socketMs));
            }
            case SQLSERVER -> {
                // SQL Server: loginTimeout in seconds (rounded up), socketTimeout in ms
                props.setProperty("loginTimeout", Long.toString(toSeconds(connectMs)));
                props.setProperty("socketTimeout", Long.toString(socketMs));
            }
            case ORACLE -> {
                // Oracle: ms
                props.setProperty("oracle.net.CONNECT_TIMEOUT", Long.toString(connectMs));
                props.setProperty("oracle.jdbc.ReadTimeout", Long.toString(socketMs));
            }
            default -> {
                // H2 and others: no well-known timeout properties; skip.
            }
        }
    }

    /**
     * Converts milliseconds to whole seconds, rounding up and never returning
     * less than 1. Drivers that treat 0 as "no timeout" would otherwise disable
     * the timeout for sub-second values.
     */
    private static long toSeconds(long millis) {
        return Math.max(1L, Math.ceilDiv(millis, 1000L));
    }

    // -------------------------------------------------------------------
    // Read-only translation
    // -------------------------------------------------------------------

    private void addReadOnlyProperties(DatabaseType type, Properties props) {
        if (type == DatabaseType.POSTGRESQL) {
            // pgjdbc's default readOnlyMode=transaction only rejects writes
            // inside a transaction; with autocommit on, writes still succeed.
            // "always" makes the whole session read-only.
            props.setProperty("readOnlyMode", "always");
        }
    }

    // -------------------------------------------------------------------
    // SSL translation
    // -------------------------------------------------------------------

    private void addSslProperties(DatabaseType type, Properties props,
                                  String jdbcUrl, Boolean ssl) {
        if (!Boolean.TRUE.equals(ssl)) {
            return;
        }

        switch (type) {
            case POSTGRESQL -> {
                // Always set sslmode=require for postgres.
                props.setProperty("sslmode", "require");
            }
            case MYSQL -> {
                props.setProperty("sslMode", "REQUIRED");
            }
            case MARIADB -> {
                // Only apply the default if the caller didn't pass a stricter sslMode.
                String existing = findExistingSslMode(jdbcUrl);
                if (existing == null || isWeakerOrEqual(existing, "trust")) {
                    props.setProperty("sslMode", "trust");
                }
            }
            case SQLSERVER -> {
                props.setProperty("encrypt", "true");
            }
            case ORACLE -> {
                if (!isTcpsUrl(jdbcUrl)) {
                    throw new IllegalArgumentException(
                            "Oracle SSL requires a TCPS URL " +
                            "(jdbc:oracle:thin:@tcps://host:port/service). " +
                            "The supplied URL does not use the TCPS scheme.");
                }
            }
            default -> {
                // H2 and others: no SSL mapping needed.
            }
        }
    }

    /**
     * Extracts the sslMode value from a JDBC URL if present.
     * Checks both ?query and ;param forms.
     */
    private static String findExistingSslMode(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        // Check ?sslMode= form
        int qIndex = lower.indexOf('?');
        if (qIndex >= 0) {
            for (String param : lower.substring(qIndex + 1).split("[&;]")) {
                int eq = param.indexOf('=');
                if (eq > 0 && "sslmode".equals(param.substring(0, eq).trim())) {
                    return param.substring(eq + 1).trim();
                }
            }
        }
        // Check ;sslMode= form (SQL Server / generic semicolon)
        for (String param : lower.split("[?&]")) {
            String after = param;
            if (after.contains(";")) {
                for (String part : after.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && "sslmode".equals(part.substring(0, eq).trim())) {
                        return part.substring(eq + 1).trim();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns true if the existing sslMode value is weaker than or equal to
     * the given threshold (i.e., we should NOT replace it because the caller
     * already specified something).
     * <p>
     * Weaker modes: disable, prefer, trust.
     * Stronger modes: verify-ca, verify-full, required.
     */
    private static boolean isWeakerOrEqual(String existing, String threshold) {
        // The "trust" mode is the weakest reasonable default.
        // If caller specified something (anything other than trust), keep it.
        // We only auto-set when there is NO existing sslMode, or it is "trust".
        return existing.equals(threshold) ||
                "disable".equalsIgnoreCase(existing) ||
                "prefer".equalsIgnoreCase(existing);
    }

    private static boolean isTcpsUrl(String jdbcUrl) {
        return jdbcUrl.toLowerCase(Locale.ROOT).contains("tcps://");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }
}
