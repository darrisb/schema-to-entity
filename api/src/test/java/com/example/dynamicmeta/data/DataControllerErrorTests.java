package com.example.dynamicmeta.data;

import com.example.dynamicmeta.connection.ConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data endpoints are called by anonymous browsers, so database failures must
 * come back as generic messages; the driver's own text stays in the server log.
 */
class DataControllerErrorTests {

    private static final String DRIVER_DETAIL =
            "ERROR: relation \"internal_payroll\" does not exist at character 15";

    private final DataController controller = new DataController(null);

    @Test
    void sqlFailureHidesTheDriverMessage() {
        assertGeneric(controller.sqlFailure(new SQLException(DRIVER_DETAIL)),
                HttpStatus.BAD_GATEWAY, DataController.QUERY_FAILED);
    }

    @Test
    void timeoutHidesTheDriverMessage() {
        assertGeneric(controller.queryTimeout(new SQLTimeoutException(DRIVER_DETAIL)),
                HttpStatus.GATEWAY_TIMEOUT, DataController.QUERY_TIMED_OUT);
    }

    @Test
    void poolTimeoutHidesTheDriverMessage() {
        assertGeneric(controller.poolTimeout(new SQLTransientConnectionException(DRIVER_DETAIL)),
                HttpStatus.SERVICE_UNAVAILABLE, DataController.SOURCE_BUSY);
    }

    @Test
    void connectionFailureHidesTheDetail() {
        assertGeneric(controller.connectionFailure(new ConnectionException(DRIVER_DETAIL)),
                HttpStatus.BAD_GATEWAY, DataController.SOURCE_UNAVAILABLE);
    }

    private static void assertGeneric(ResponseEntity<Map<String, String>> response,
                                      HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("error", message);
        assertThat(response.getBody().get("error")).doesNotContain("internal_payroll");
    }
}
