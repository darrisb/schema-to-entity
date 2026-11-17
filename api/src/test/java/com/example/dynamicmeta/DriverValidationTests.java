package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.UnsupportedDatabaseException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DS-004: Verify that {@link SchemaDiscoveryService#discover(SchemaDiscoveryService.ConnectionRequest)}
 * validates the caller-supplied {@code driverClassName} against the expected driver derived
 * from the {@code DatabaseType}, and never loads an arbitrary caller-supplied class.
 */
class DriverValidationTests {

    private static SchemaDiscoveryService service() {
        // explicitConnectionsEnabled defaults to true
        return new SchemaDiscoveryService(
                new JdbcDataSource(), new SchemaToEntityProperties(),
                new ConnectionPolicy(List.of(), true));
    }

    @Test
    void mismatchedDriverClassNameThrowsIllegalArgument() throws SQLException {
        SchemaDiscoveryService service = service();

        SchemaDiscoveryService.ConnectionRequest request =
                new SchemaDiscoveryService.ConnectionRequest(
                        "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
                        "sa", "", null, null,
                        "com.mysql.cj.jdbc.Driver", // wrong driver for H2
                        null);

        assertThatThrownBy(() -> service.discover(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.h2.Driver");
    }

    @Test
    void matchingDriverClassNameIsAccepted() throws SQLException {
        SchemaDiscoveryService service = service();

        SchemaDiscoveryService.ConnectionRequest request =
                new SchemaDiscoveryService.ConnectionRequest(
                        "jdbc:h2:mem:testAccepted;DB_CLOSE_DELAY=-1",
                        "sa", "", null, null,
                        "org.h2.Driver", // correct driver for H2
                        null);

        // The driver class is accepted; discover proceeds and connects successfully
        // because H2 in-memory databases are created on the fly.
        Map<String, Object> result = service.discover(request);
        assertThat(result).containsEntry("dialect", "h2");
    }

    @Test
    void absentDriverThrowsUnsupportedDatabaseWithDriverName() throws SQLException {
        SchemaDiscoveryService service = service();

        // MySQL driver is not on the test classpath at all.
        SchemaDiscoveryService.ConnectionRequest request =
                new SchemaDiscoveryService.ConnectionRequest(
                        "jdbc:mysql://localhost:3306/testdb",
                        "sa", "", null, null,
                        null, // no driverClassName supplied — let the service detect it
                        null);

        assertThatThrownBy(() -> service.discover(request))
                .isInstanceOf(UnsupportedDatabaseException.class)
                .hasMessageContaining("com.mysql.cj.jdbc.Driver");
    }
}
