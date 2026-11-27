package com.example.dynamicmeta.data;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.connection.ConnectionException;
import com.example.dynamicmeta.connection.SecretRedactor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Read-only REST endpoints for the named data sources.
 * <p>
 * Only GET mappings exist. Row responses use the {@code items} envelope so a
 * REST data source configured with {@code responsePath: "items"} can consume
 * them directly. Every error is returned as {@code {"error": ...}} with the
 * message passed through {@link SecretRedactor}.
 * <p>
 * DR-008: every successful response carries the {@value #SCHEMA_VERSION_HEADER}
 * header naming the schema version the data was read from, and each entity
 * descriptor in the listing repeats it as {@code schemaVersion}, so the
 * listing keeps its historical bare-array response shape. The version comes
 * from the cached schema entry, so it costs nothing per request. Error
 * responses carry no version: nothing was served.
 */
@RestController
@RequestMapping("/api/data")
public class DataController {

    /**
     * Header naming the schema version a successful data response was read
     * from (DR-008). Registered as a CORS exposed header by
     * {@link DataCorsConfiguration} so browsers can read it.
     */
    public static final String SCHEMA_VERSION_HEADER = "X-Schema-Version";

    /**
     * Messages returned for database-side failures. The data endpoints are meant
     * to be called by anonymous browsers, so driver messages (which can name
     * tables, quote SQL or describe the server) are logged, never returned.
     */
    static final String QUERY_FAILED = "Database query failed";
    static final String QUERY_TIMED_OUT = "Database query timed out";
    static final String SOURCE_BUSY = "Data source is busy; try again shortly";
    static final String SOURCE_UNAVAILABLE = "Data source is unavailable";

    private static final Logger log = LoggerFactory.getLogger(DataController.class);

    private final DataQueryService queryService;

    public DataController(DataQueryService queryService) {
        this.queryService = queryService;
    }

    /** Lists the entities discovered for a source, each carrying its schema version. */
    @GetMapping("/{source}")
    public ResponseEntity<List<DataQueryService.EntityDescriptor>> entities(
            @PathVariable String source) {
        return versioned(source, queryService.entities(source));
    }

    /** Returns one page of rows for an entity. */
    @GetMapping("/{source}/{entity}")
    public ResponseEntity<DataQueryService.DataPage> rows(
            @PathVariable String source,
            @PathVariable String entity,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(name = "total", defaultValue = "true") boolean total)
            throws SQLException {
        DataQueryService.DataPage page =
                queryService.query(source, entity, withoutControlParameters(parameters), total,
                        parameters.getOrDefault("expand", List.of()));
        return versioned(source, page);
    }

    /** Returns grouped aggregate rows for an entity. */
    @GetMapping("/{source}/{entity}/aggregate")
    public ResponseEntity<DataQueryService.AggregateResult> aggregate(
            @PathVariable String source,
            @PathVariable String entity,
            @RequestParam MultiValueMap<String, String> parameters) throws SQLException {
        return versioned(source, queryService.aggregate(source, entity, parameters));
    }

    /** Returns one row by its single-column key. */
    @GetMapping("/{source}/{entity}/{key}")
    public ResponseEntity<Map<String, Object>> byKey(
            @PathVariable String source,
            @PathVariable String entity,
            @PathVariable String key) throws SQLException {
        return versioned(source, queryService.byKey(source, entity, key));
    }

    /** A successful body plus the version of the cached schema it was read from. */
    private <T> ResponseEntity<T> versioned(String source, T body) {
        return ResponseEntity.ok()
                .header(SCHEMA_VERSION_HEADER, queryService.schemaVersion(source))
                .body(body);
    }

    /** The {@code total} switch is handled here, not by the query parser. */
    private static Map<String, List<String>> withoutControlParameters(
            MultiValueMap<String, String> parameters) {
        Map<String, List<String>> queryParameters = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (!"total".equals(name) && !"expand".equals(name)) {
                queryParameters.put(name, values);
            }
        });
        return queryParameters;
    }

    @ExceptionHandler(SourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> unknownSource(
            SourceNotFoundException exception) {
        return notFound(exception);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> unknownEntity(
            EntityNotFoundException exception) {
        return notFound(exception);
    }

    @ExceptionHandler(RowNotFoundException.class)
    public ResponseEntity<Map<String, String>> missingRow(RowNotFoundException exception) {
        return notFound(exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(error(exception.getMessage()));
    }

    /** A malformed {@code total} (or other typed) parameter is a client error. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> badParameter(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(error("Invalid value for parameter '" + exception.getName() + "'"));
    }

    @ExceptionHandler(SQLTimeoutException.class)
    public ResponseEntity<Map<String, String>> queryTimeout(SQLTimeoutException exception) {
        logFailure("Data query timed out", exception);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(error(QUERY_TIMED_OUT));
    }

    @ExceptionHandler(SQLTransientConnectionException.class)
    public ResponseEntity<Map<String, String>> poolTimeout(
            SQLTransientConnectionException exception) {
        logFailure("No pooled connection available", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(SOURCE_BUSY));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, String>> sqlFailure(SQLException exception) {
        logFailure("Data query failed", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(QUERY_FAILED));
    }

    @ExceptionHandler(ConnectionException.class)
    public ResponseEntity<Map<String, String>> connectionFailure(
            ConnectionException exception) {
        logFailure("Data source connection failed", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(SOURCE_UNAVAILABLE));
    }

    /** Logs the real failure for operators; the message is still redacted. */
    private static void logFailure(String summary, Exception exception) {
        log.warn("{}: {}", summary, SecretRedactor.redact(exception.getMessage()));
        log.debug(summary, exception);
    }

    private static ResponseEntity<Map<String, String>> notFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(exception.getMessage()));
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", SecretRedactor.redact(message));
    }
}
