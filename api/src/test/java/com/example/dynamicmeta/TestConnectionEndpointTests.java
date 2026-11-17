package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionRejectedException;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.connection.ParsedConnection;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestConnectionEndpointTests {

    private static final ConnectionPolicy ALLOW_EMBEDDED = new ConnectionPolicy(List.of(), true);

    @Test
    void h2ConnectionReturnsDatabaseInfo() throws Exception {
        JdbcDataSource ds = dataSource("testconn");
        createMinimalSchema(ds);

        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setAllowEmbeddedDatabases(true);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                ds, props, ALLOW_EMBEDDED, factory());

        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:testconn;DB_CLOSE_DELAY=-1", "sa", "", "PUBLIC",
                List.of(), null, null);

        Map<String, Object> result = service.testConnection(request);

        assertThat(result).containsKey("databaseType");
        assertThat(result).containsKey("productName");
        assertThat(result).containsKey("productVersion");
        assertThat(result).containsKey("driverName");
        assertThat(result).containsKey("driverVersion");
        assertThat(result).containsKey("readOnly");
        assertThat(result).containsKey("latencyMs");

        assertThat(result.get("databaseType")).isEqualTo("h2");
        assertThat((String) result.get("productName")).containsIgnoringCase("h2");
        // The factory requests a read-only session, but H2's isReadOnly()
        // reports the database state and stays read-write for in-memory DBs.
        assertThat((Boolean) result.get("readOnly")).isFalse();
        assertThat(((Number) result.get("latencyMs")).longValue()).isGreaterThanOrEqualTo(0L);

        // Ensure no credentials leak in the response
        String body = result.toString();
        assertThat(body).doesNotContain("sa");
        assertThat(body).doesNotContain("jdbc:h2");
        assertThat(body).doesNotContain("testconn");
    }

    @Test
    void latencyMsIncludesOpeningTheConnection() throws Exception {
        JdbcDataSource ds = dataSource("testconn4");
        createMinimalSchema(ds);

        JdbcConnectionFactory slowFactory = new JdbcConnectionFactory(Duration.ZERO, Duration.ZERO) {
            @Override
            public Connection open(ParsedConnection parsed, String username, String password, Boolean ssl)
                    throws SQLException {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ds.getConnection();
            }
        };

        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setAllowEmbeddedDatabases(true);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                ds, props, ALLOW_EMBEDDED, slowFactory);

        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:testconn4;DB_CLOSE_DELAY=-1", "sa", "", "PUBLIC",
                List.of(), null, null);

        Map<String, Object> result = service.testConnection(request);

        assertThat(((Number) result.get("latencyMs")).longValue()).isGreaterThanOrEqualTo(30L);
    }

    @Test
    void disabledExplicitConnectionsReturns403() throws Exception {
        JdbcDataSource ds = dataSource("testconn2");
        createMinimalSchema(ds);

        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setExplicitConnectionsEnabled(false);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                ds, props, ALLOW_EMBEDDED, factory());

        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:testconn2;DB_CLOSE_DELAY=-1", "sa", "s3cretPass", "PUBLIC",
                List.of(), null, null);

        assertThatThrownBy(() -> service.testConnection(request))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessage("Explicit database connections are disabled");
    }

    @Test
    void responseContainsNoCredentialValues() throws Exception {
        JdbcDataSource ds = dataSource("testconn3");
        createMinimalSchema(ds);

        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setAllowEmbeddedDatabases(true);
        SchemaDiscoveryService service = new SchemaDiscoveryService(
                ds, props, ALLOW_EMBEDDED, factory());

        // Send credentials in the request body; they must never appear in the response
        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:testconn3;DB_CLOSE_DELAY=-1", "sa", "", "PUBLIC",
                List.of(), null, null);

        Map<String, Object> result = service.testConnection(request);
        String body = result.toString();

        // Response should never contain URL, username or password
        assertThat(body).doesNotContain("jdbc:h2");
        assertThat(body).doesNotContain("testconn3");
        // The response map contains only: databaseType, productName, productVersion,
        // driverName, driverVersion, readOnly, latencyMs — none of which are credentials
        assertThat(body).contains("databaseType");
        assertThat(body).contains("productName");
        assertThat(body).contains("readOnly");
        assertThat(body).contains("latencyMs");
    }

    private static JdbcConnectionFactory factory() {
        return new JdbcConnectionFactory(Duration.ZERO, Duration.ZERO);
    }

    private static JdbcDataSource dataSource(String database) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createMinimalSchema(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists t (id bigint primary key)");
        }
    }
}
