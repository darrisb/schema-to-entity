package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionBusyException;
import com.example.dynamicmeta.connection.ConnectionPolicy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitConnectionLimitTests {

    private static final ConnectionPolicy DEFAULT_POLICY = new ConnectionPolicy(List.of(), true);

    /**
     * With a limit of 1, a second concurrent call should get ConnectionBusyException.
     * A latch holds the first call open so the second call cannot acquire the permit.
     */
    @Test
    void secondConcurrentCallGetsBusyExceptionWhenLimitIsOne() throws Exception {
        JdbcDataSource host = dataSource("host");
        createSchema(host);

        JdbcDataSource external = dataSource("external");
        createSchema(external);

        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch secondMayProceed = new CountDownLatch(1);

        JdbcConnectionFactory factory = new JdbcConnectionFactory(Duration.ZERO, Duration.ZERO) {
            @Override
            public Connection open(ParsedConnection parsed, String username, String password, Boolean ssl)
                    throws SQLException {
                firstAcquired.countDown();
                // Hold the permit until the second call has attempted
                try {
                    secondMayProceed.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return external.getConnection();
            }
        };

        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setExplicitConnectionsEnabled(true);
        props.setAllowEmbeddedDatabases(true);
        props.setMaxConcurrentExplicitConnections(1);

        SchemaDiscoveryService service = new SchemaDiscoveryService(
                host, props, DEFAULT_POLICY, factory);

        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:external;DB_CLOSE_DELAY=-1", "sa", "", "PUBLIC",
                List.of(), null, null);

        AtomicReference<Exception> secondException = new AtomicReference<>();

        Thread firstThread = new Thread(() -> {
            try {
                service.discover(request);
            } catch (Exception e) {
                // first thread should succeed, exceptions are unexpected
            }
        });

        Thread secondThread = new Thread(() -> {
            try {
                // Wait for the first thread to acquire its permit
                firstAcquired.await();
                try {
                    service.discover(request);
                } catch (Exception e) {
                    secondException.set(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        firstThread.start();
        secondThread.start();

        secondThread.join(5000);
        secondMayProceed.countDown(); // Release the first thread
        firstThread.join(5000);

        assertThat(secondException.get())
                .isInstanceOf(ConnectionBusyException.class)
                .hasMessage("Too many concurrent schema extractions; try again shortly");
    }

    /**
     * When a connection attempt fails (SQLException from factory), the permit must be
     * released so a subsequent call can succeed.
     */
    @Test
    void permitReleasedAfterFailedConnection() throws Exception {
        JdbcDataSource host = dataSource("host2");
        createSchema(host);

        JdbcDataSource external = dataSource("external2");
        createSchema(external);

        // Factory that fails the first call and succeeds on the second
        AtomicBoolean shouldFail = new AtomicBoolean(true);

        JdbcConnectionFactory factory = new JdbcConnectionFactory(Duration.ZERO, Duration.ZERO) {
            @Override
            public Connection open(ParsedConnection parsed, String username, String password, Boolean ssl)
                    throws SQLException {
                if (shouldFail.compareAndSet(true, false)) {
                    throw new SQLException("simulated connection failure");
                }
                return external.getConnection();
            }
        };

        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setExplicitConnectionsEnabled(true);
        props.setAllowEmbeddedDatabases(true);
        props.setMaxConcurrentExplicitConnections(1);

        SchemaDiscoveryService service = new SchemaDiscoveryService(
                host, props, DEFAULT_POLICY, factory);

        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:external2;DB_CLOSE_DELAY=-1", "sa", "", "PUBLIC",
                List.of("CUSTOMERS"), null, null);

        // First call: connection fails, but permit should be released
        assertThatThrownBy(() -> service.discover(request))
                .isInstanceOf(SQLException.class)
                .hasMessage("simulated connection failure");

        // Second call: should succeed because the permit was released
        Map<String, Object> result = service.discover(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).extracting(table -> table.get("name")).containsExactly("CUSTOMERS");
    }

    private static JdbcDataSource dataSource(String database) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint generated by default as identity primary key, name varchar(80) not null)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null, constraint fk_customer foreign key (customer_id) references customers(id))");
        }
    }
}
