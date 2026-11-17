package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionBusyException;
import com.example.dynamicmeta.connection.ConnectionException;
import com.example.dynamicmeta.connection.ConnectionRejectedException;
import com.example.dynamicmeta.connection.UnsupportedDatabaseException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaDiscoveryControllerErrorTests {

    private static SpringMetadataTransformer transformer() {
        return new SpringMetadataTransformer();
    }

    /** A no-op DataSource for creating the controller/service without real connections. */
    private static DataSource stubDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:stub;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    // --- ConnectionRejectedException -> 403 ---

    @Test
    void connectionRejectedExceptionReturns403() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.connectionRejected(
                new ConnectionRejectedException("Explicit database connections are disabled"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Explicit database connections are disabled");
    }

    @Test
    void connectionRejectedExceptionRedactsCredentials() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.connectionRejected(
                new ConnectionRejectedException("Refused connection to postgres://admin:s3cret@host/db"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String error = response.getBody().get("error");
        assertThat(error).contains("***");
        assertThat(error).doesNotContain("s3cret");
    }

    @Test
    void disabledExplicitConnectionsReturns403() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:errtest;DB_CLOSE_DELAY=-1");
        SchemaToEntityProperties props = new SchemaToEntityProperties();
        props.setExplicitConnectionsEnabled(false);
        SchemaDiscoveryService service = new SchemaDiscoveryService(ds, props);

        SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                "jdbc:h2:mem:errtest;DB_CLOSE_DELAY=-1", "sa", "", null, null, null, null);

        assertThatThrownBy(() -> service.discover(request))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessage("Explicit database connections are disabled");
    }

    // --- IllegalArgumentException -> 400 ---

    @Test
    void illegalArgumentExceptionReturns400() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.badRequest(
                new IllegalArgumentException("A connectionString is required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "A connectionString is required");
    }

    // --- UnsupportedDatabaseException -> 422 ---

    @Test
    void unsupportedDatabaseExceptionReturns422() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.unsupportedDatabase(
                new UnsupportedDatabaseException("Database type 'cassandra' is not supported"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("error", "Database type 'cassandra' is not supported");
    }

    // --- ConnectionBusyException -> 429 ---

    @Test
    void connectionBusyExceptionReturns429() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.connectionBusy(
                new ConnectionBusyException("Too many concurrent connections"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).containsEntry("error", "Too many concurrent connections");
    }

    // --- SQLTimeoutException -> 504 ---

    @Test
    void sqlTimeoutExceptionReturns504() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.connectionTimeout(
                new SQLTimeoutException("Operation timed out after 30 seconds"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).containsEntry("error", "Operation timed out after 30 seconds");
    }

    // --- SQLException (non-timeout) -> 502 ---

    @Test
    void sqlExceptionReturns502() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.connectionFailure(
                new SQLTransientConnectionException("Connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("error", "Connection refused");
    }

    // --- SQLException redacts credentials ---

    @Test
    void sqlExceptionRedactsCredentials() {
        SchemaDiscoveryController controller = new SchemaDiscoveryController(
                new SchemaDiscoveryService(stubDataSource(), new SchemaToEntityProperties()), transformer());

        ResponseEntity<Map<String, String>> response = controller.connectionFailure(
                new SQLTransientConnectionException("Failed to connect to postgres://user:pass123@host/db"));

        String error = response.getBody().get("error");
        assertThat(error).contains("***");
        assertThat(error).doesNotContain("pass123");
    }

    // --- Integration: Spring context verifies 403 when disabled ---

    @Test
    void disabledExplicitConnectionsViaContextReturns403() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> stubDataSource())
                .withPropertyValues("schema-to-entity.explicit-connections-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(SchemaDiscoveryController.class);
                    SchemaDiscoveryService service = context.getBean(SchemaDiscoveryService.class);

                    SchemaDiscoveryService.ConnectionRequest request = new SchemaDiscoveryService.ConnectionRequest(
                            "jdbc:h2:mem:errtest", "sa", "", null, null, null, null);

                    assertThatThrownBy(() -> service.discover(request))
                            .isInstanceOf(ConnectionRejectedException.class)
                            .hasMessage("Explicit database connections are disabled");
                });
    }

    // --- Exception hierarchy check ---

    @Test
    void connectionExceptionsFormProperHierarchy() {
        assertThat(new ConnectionRejectedException("test")).isInstanceOf(ConnectionException.class);
        assertThat(new UnsupportedDatabaseException("test")).isInstanceOf(ConnectionException.class);
        assertThat(new ConnectionBusyException("test")).isInstanceOf(ConnectionException.class);
    }
}
