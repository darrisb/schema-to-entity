package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.ParsedConnection;
import com.example.dynamicmeta.connection.SecretRedactor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the immutable set of named data source definitions from configuration.
 * <p>
 * Every source is validated eagerly at startup. A source is rejected when its
 * name does not match {@code [a-z0-9-]{1,40}}, its URL cannot be parsed or does
 * not pass the {@link ConnectionPolicy}, or its pool size is outside 1-20.
 * Validation errors are reported as {@link IllegalStateException} naming the
 * source and the problem. Error messages never include the configured URL or
 * password.
 * <p>
 * This class only validates configuration; it does not open connections.
 */
public final class DataSourceDefinitions {

    /** Source names must be 1-40 characters of lowercase letters, digits or hyphens. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9-]{1,40}");

    private static final int MIN_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 20;

    private DataSourceDefinitions() {
    }

    /**
     * Validate every configured source and return immutable definitions keyed
     * by source name. An empty configuration yields an empty map.
     *
     * @throws IllegalStateException when a source is invalid; the message names
     *                               the source and the problem, never the URL
     *                               or password
     */
    public static Map<String, DataSourceDefinition> from(
            SchemaToEntityProperties properties,
            ConnectionStringParser parser,
            ConnectionPolicy policy) {

        Map<String, SourceProperties> sources = properties.getSources();
        if (sources == null || sources.isEmpty()) {
            return Map.of();
        }

        Map<String, DataSourceDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, SourceProperties> entry : sources.entrySet()) {
            String name = entry.getKey();
            SourceProperties source = entry.getValue();
            if (source == null) {
                throw invalid(String.valueOf(name), "source configuration is missing");
            }

            validateName(name);
            ParsedConnection parsed = parse(name, source.getUrl(), parser);
            validatePolicy(name, parsed, policy);
            validatePoolSize(name, source.getMaxPoolSize());

            definitions.put(name, toDefinition(name, source, parsed));
        }
        return Collections.unmodifiableMap(definitions);
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw invalid(String.valueOf(name), "name must match [a-z0-9-]{1,40}");
        }
    }

    private static ParsedConnection parse(String name, String url, ConnectionStringParser parser) {
        if (url == null || url.isBlank()) {
            throw invalid(name, "url is required");
        }
        try {
            return parser.parse(url);
        } catch (RuntimeException ex) {
            throw invalid(name, problem(ex));
        }
    }

    private static void validatePolicy(String name, ParsedConnection parsed, ConnectionPolicy policy) {
        try {
            policy.validate(parsed);
        } catch (RuntimeException ex) {
            throw invalid(name, problem(ex));
        }
    }

    private static void validatePoolSize(String name, int maxPoolSize) {
        if (maxPoolSize < MIN_POOL_SIZE || maxPoolSize > MAX_POOL_SIZE) {
            throw invalid(name, "maxPoolSize must be between " + MIN_POOL_SIZE
                    + " and " + MAX_POOL_SIZE);
        }
    }

    private static DataSourceDefinition toDefinition(String name, SourceProperties source,
                                                     ParsedConnection parsed) {
        String username = source.getUsername() != null ? source.getUsername() : parsed.username();
        String password = source.getPassword() != null ? source.getPassword() : parsed.password();
        return new DataSourceDefinition(
                name,
                parsed.jdbcUrl(),
                username,
                password,
                source.getSchema(),
                source.getTables(),
                source.getSsl(),
                source.getMaxPoolSize(),
                source.getViewKeys());
    }

    /**
     * Turn a validation failure into a message that describes the problem but
     * never leaks the URL or password.
     */
    private static String problem(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return SecretRedactor.redact(message);
    }

    private static IllegalStateException invalid(String name, String problem) {
        return new IllegalStateException("Invalid data source '" + name + "': " + problem);
    }
}
