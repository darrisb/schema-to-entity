package com.example.dynamicmeta.connection;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConnectionFactoryTests {

    private static final Duration CONNECT = Duration.ofSeconds(10);
    private static final Duration SOCKET = Duration.ofSeconds(60);
    private static final JdbcConnectionFactory factory = new JdbcConnectionFactory(CONNECT, SOCKET);

    // -------------------------------------------------------------------
    // Timeout property translation
    // -------------------------------------------------------------------

    @Test
    void postgresTimeoutsInSeconds() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/testdb", "localhost", 5432, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props.getProperty("connectTimeout")).isEqualTo("10");
        assertThat(props.getProperty("socketTimeout")).isEqualTo("60");
    }

    @Test
    void mysqlTimeoutsInMilliseconds() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.MYSQL,
                "jdbc:mysql://localhost:3306/testdb", "localhost", 3306, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props.getProperty("connectTimeout")).isEqualTo("10000");
        assertThat(props.getProperty("socketTimeout")).isEqualTo("60000");
    }

    @Test
    void mariadbTimeoutsInMilliseconds() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.MARIADB,
                "jdbc:mariadb://localhost:3306/testdb", "localhost", 3306, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props.getProperty("connectTimeout")).isEqualTo("10000");
        assertThat(props.getProperty("socketTimeout")).isEqualTo("60000");
    }

    @Test
    void sqlServerTimeoutsMixedUnits() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.SQLSERVER,
                "jdbc:sqlserver://localhost:1433;databaseName=testdb", "localhost", 1433, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props.getProperty("loginTimeout")).isEqualTo("10");
        assertThat(props.getProperty("socketTimeout")).isEqualTo("60000");
    }

    @Test
    void oracleTimeoutsInMilliseconds() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.ORACLE,
                "jdbc:oracle:thin:@//localhost:1521/testdb", "localhost", 1521, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props.getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("10000");
        assertThat(props.getProperty("oracle.jdbc.ReadTimeout")).isEqualTo("60000");
    }

    @Test
    void h2NoTimeoutProperties() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.H2,
                "jdbc:h2:mem:testdb", null, null, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props).doesNotContainKeys(
                "connectTimeout", "socketTimeout", "loginTimeout",
                "oracle.net.CONNECT_TIMEOUT", "oracle.jdbc.ReadTimeout");
    }

    // -------------------------------------------------------------------
    // SSL property translation
    // -------------------------------------------------------------------

    @Test
    void postgresSslMapsToSslModeRequire() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/testdb", "localhost", 5432, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", true);

        assertThat(props.getProperty("sslmode")).isEqualTo("require");
    }

    @Test
    void mysqlSslMapsToSslModeRequired() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.MYSQL,
                "jdbc:mysql://localhost:3306/testdb", "localhost", 3306, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", true);

        assertThat(props.getProperty("sslMode")).isEqualTo("REQUIRED");
    }

    @Test
    void mariadbSslMapsToSslModeTrust() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.MARIADB,
                "jdbc:mariadb://localhost:3306/testdb", "localhost", 3306, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", true);

        assertThat(props.getProperty("sslMode")).isEqualTo("trust");
    }

    @Test
    void sqlServerSslMapsToEncryptTrue() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.SQLSERVER,
                "jdbc:sqlserver://localhost:1433;databaseName=testdb", "localhost", 1433, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", true);

        assertThat(props.getProperty("encrypt")).isEqualTo("true");
    }

    @Test
    void oracleSslWithTcpsUrlIsAccepted() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.ORACLE,
                "jdbc:oracle:thin:@tcps://localhost:2484/testdb", "localhost", 2484, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", true);

        // No special property is added for Oracle SSL; TCPS URL is sufficient.
        assertThat(props).doesNotContainKey("sslmode");
    }

    @Test
    void oracleSslWithNonTcpsUrlIsRejected() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.ORACLE,
                "jdbc:oracle:thin:@//localhost:1521/testdb", "localhost", 1521, "testdb");

        assertThatThrownBy(() -> factory.buildProperties(parsed, "u", "p", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TCPS");
    }

    @Test
    void sslNullDoesNotSetSslProperties() {
        ParsedConnection parsed = ParsedConnection.of(DatabaseType.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/testdb", "localhost", 5432, "testdb");
        Properties props = factory.buildProperties(parsed, "u", "p", null);

        assertThat(props).doesNotContainKey("sslmode");
    }

    // -------------------------------------------------------------------
    // Username/password override
    // -------------------------------------------------------------------

    @Test
    void requestCredentialsOverrideParsedCredentials() {
        ParsedConnection parsed = new ParsedConnection(
                DatabaseType.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/testdb", "localhost", 5432, "testdb",
                "urlUser", "urlPass", new java.util.LinkedHashMap<>());

        Properties props = factory.buildProperties(parsed, "reqUser", "reqPass", null);

        assertThat(props.getProperty("user")).isEqualTo("reqUser");
        assertThat(props.getProperty("password")).isEqualTo("reqPass");
    }

    @Test
    void fallsBackToParsedCredentialsWhenRequestIsBlank() {
        ParsedConnection parsed = new ParsedConnection(
                DatabaseType.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/testdb", "localhost", 5432, "testdb",
                "urlUser", "urlPass", new java.util.LinkedHashMap<>());

        Properties props = factory.buildProperties(parsed, "", "  ", null);

        assertThat(props.getProperty("user")).isEqualTo("urlUser");
        assertThat(props.getProperty("password")).isEqualTo("urlPass");
    }

    @Test
    void credentialsFromUrlParametersReachProperties() {
        ParsedConnection parsed = new ConnectionStringParser().parse(
                "jdbc:postgresql://localhost:5432/testdb?user=urlUser&password=urlPass");

        Properties props = factory.buildProperties(parsed, null, null, null);

        assertThat(props.getProperty("user")).isEqualTo("urlUser");
        assertThat(props.getProperty("password")).isEqualTo("urlPass");
    }

    @Test
    void requestCredentialsStillOverrideUrlParameterCredentials() {
        ParsedConnection parsed = new ConnectionStringParser().parse(
                "jdbc:postgresql://localhost:5432/testdb?user=urlUser&password=urlPass");

        Properties props = factory.buildProperties(parsed, "reqUser", "reqPass", null);

        assertThat(props.getProperty("user")).isEqualTo("reqUser");
        assertThat(props.getProperty("password")).isEqualTo("reqPass");
    }

    // -------------------------------------------------------------------
    // Read-only connection (H2 embedded)
    // -------------------------------------------------------------------

    @Test
    void h2ConnectionOpensSuccessfully() throws Exception {
        DataSource ds = h2DataSource("readonly-h2");
        createSchema(ds);

        ParsedConnection parsed = ParsedConnection.of(DatabaseType.H2,
                "jdbc:h2:mem:readonly-h2;DB_CLOSE_DELAY=-1", null, null, "readonly-h2");

        // Verify the factory opens a usable connection without throwing.
        // setReadOnly(true) is called in the factory; enforcement is driver-dependent.
        try (Connection conn = factory.open(parsed, "sa", "", null)) {
            assertThat(conn).isNotNull();
            assertThat(conn.isClosed()).isFalse();
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            assertThat(meta.getDatabaseProductName()).contains("H2");
        }
    }

    // -------------------------------------------------------------------
    // Custom timeout durations
    // -------------------------------------------------------------------

    @Test
    void customTimeoutsAreTranslated() {
        JdbcConnectionFactory customFactory = new JdbcConnectionFactory(
                Duration.ofSeconds(5), Duration.ofSeconds(120));

        ParsedConnection parsed = ParsedConnection.of(DatabaseType.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/testdb", "localhost", 5432, "testdb");
        Properties props = customFactory.buildProperties(parsed, "u", "p", null);

        assertThat(props.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(props.getProperty("socketTimeout")).isEqualTo("120");
    }

    // -------------------------------------------------------------------
    // Refusal message
    // -------------------------------------------------------------------

    @Test
    void refusalMessageNamesTypeAndHostWithoutUrlOrCredentials() throws Exception {
        Driver refusingDriver = new RefusingDriver();
        DriverManager.registerDriver(refusingDriver);
        try {
            // The scheme is unknown to every real driver, so the stub is selected.
            ParsedConnection parsed = new ParsedConnection(
                    DatabaseType.POSTGRESQL,
                    "jdbc:refuse://user:secret@dbhost:5432/appdb",
                    "dbhost", 5432, "appdb", "user", "secret", new LinkedHashMap<>());

            assertThatThrownBy(() -> factory.open(parsed, null, null, null))
                    .isInstanceOf(ConnectionException.class)
                    .hasMessageContaining("postgresql")
                    .hasMessageContaining("POSTGRESQL")
                    .hasMessageContaining("dbhost")
                    .hasMessageNotContaining("jdbc:refuse://user:secret@dbhost:5432/appdb")
                    .hasMessageNotContaining("secret");
        } finally {
            DriverManager.deregisterDriver(refusingDriver);
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /** A driver that accepts every URL but never opens a connection. */
    private static final class RefusingDriver implements Driver {
        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private static JdbcDataSource h2DataSource(String database) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table customers (id bigint primary key, name varchar(80) not null)");
        }
    }
}
