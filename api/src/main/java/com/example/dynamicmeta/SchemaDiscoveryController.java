package com.example.dynamicmeta;

import com.example.dynamicmeta.connection.ConnectionBusyException;
import com.example.dynamicmeta.connection.ConnectionRejectedException;
import com.example.dynamicmeta.connection.SecretRedactor;
import com.example.dynamicmeta.connection.UnsupportedDatabaseException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLTimeoutException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schema")
public class SchemaDiscoveryController {

    private final SchemaDiscoveryService discoveryService;
    private final SpringMetadataTransformer metadataTransformer;

    public SchemaDiscoveryController(
            SchemaDiscoveryService discoveryService, SpringMetadataTransformer metadataTransformer) {
        this.discoveryService = discoveryService;
        this.metadataTransformer = metadataTransformer;
    }

    @GetMapping({"", "/datasource"})
    public Map<String, Object> fromApplicationDataSource(
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) List<String> tables) throws SQLException {
        return discoveryService.discover(schema, tables == null ? List.of() : tables);
    }

    @GetMapping("/sample")
    public Map<String, Object> sample() {
        return discoveryService.sample();
    }

    @PostMapping({"/extract", "/extract-postgres"})
    public Map<String, Object> fromConnectionString(
            @RequestBody SchemaDiscoveryService.ConnectionRequest request) throws SQLException {
        return discoveryService.discover(request);
    }

    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(
            @RequestBody SchemaDiscoveryService.ConnectionRequest request) throws SQLException {
        return discoveryService.testConnection(request);
    }

    @PostMapping("/to-spring-metadata")
    public Map<String, Object> toSpringMetadata(@RequestBody MetadataRequest request) {
        if (request == null || request.schema() == null) {
            throw new IllegalArgumentException("A schema is required");
        }
        return metadataTransformer.transform(request.schema(),
                request.options() == null ? null : request.options().entityNamePrefix());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", SecretRedactor.redact(exception.getMessage())));
    }

    @ExceptionHandler(ConnectionRejectedException.class)
    public ResponseEntity<Map<String, String>> connectionRejected(ConnectionRejectedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", SecretRedactor.redact(exception.getMessage())));
    }

    @ExceptionHandler(UnsupportedDatabaseException.class)
    public ResponseEntity<Map<String, String>> unsupportedDatabase(UnsupportedDatabaseException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", SecretRedactor.redact(exception.getMessage())));
    }

    @ExceptionHandler(ConnectionBusyException.class)
    public ResponseEntity<Map<String, String>> connectionBusy(ConnectionBusyException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", SecretRedactor.redact(exception.getMessage())));
    }

    @ExceptionHandler(SQLTimeoutException.class)
    public ResponseEntity<Map<String, String>> connectionTimeout(SQLTimeoutException exception) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", SecretRedactor.redact(exception.getMessage())));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, String>> connectionFailure(SQLException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", SecretRedactor.redact(exception.getMessage())));
    }

    public record MetadataRequest(Map<String, Object> schema, Options options) {
        public record Options(String entityNamePrefix) {
        }
    }
}
