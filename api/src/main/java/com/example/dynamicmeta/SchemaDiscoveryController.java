package com.example.dynamicmeta;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/to-spring-metadata")
    public Map<String, Object> toSpringMetadata(@RequestBody MetadataRequest request) {
        if (request == null || request.schema() == null) {
            throw new IllegalArgumentException("A schema is required");
        }
        return metadataTransformer.transform(request.schema(),
                request.options() == null ? null : request.options().entityNamePrefix());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", redact(exception.getMessage())));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, String>> connectionFailure(SQLException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", redact(exception.getMessage())));
    }

    private static String redact(String message) {
        if (message == null) return "Database operation failed";
        return message.replaceAll("(?i)(postgres(?:ql)?://[^:/@\\s]+):[^@\\s]+@", "$1:***@");
    }

    public record MetadataRequest(Map<String, Object> schema, Options options) {
        public record Options(String entityNamePrefix) {
        }
    }
}
