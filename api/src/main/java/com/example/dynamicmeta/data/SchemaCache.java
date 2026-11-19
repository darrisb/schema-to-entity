package com.example.dynamicmeta.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.connection.ConnectionException;

/**
 * Caches one discovered {@link SourceSchema} per configured source.
 * <p>
 * The first {@link #get(String)} for a source opens a connection from the
 * {@link SourceRegistry}, discovers the schema with
 * {@link SchemaDiscoveryService#inspect(Connection, String, List)} and caches
 * the typed result. Subsequent calls reuse the cached schema until
 * {@code schema-to-entity.schema-cache-ttl} elapses; {@link #refresh(String)}
 * forces rediscovery. The source's configured tables allowlist and view keys
 * are applied during discovery.
 * <p>
 * Concurrent first calls for one source are serialized so the schema is
 * discovered only once. The data API is read-only: discovery reads JDBC
 * metadata and never issues INSERT, UPDATE, DELETE or DDL.
 */
public class SchemaCache {

    private final SourceRegistry registry;
    private final SchemaDiscoveryService discoveryService;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, CachedSchema> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SchemaCache(SourceRegistry registry,
                       SchemaDiscoveryService discoveryService,
                       SchemaToEntityProperties properties) {
        this(registry, discoveryService, properties, Clock.systemUTC());
    }

    /**
     * Constructor used by tests that need to control time.
     *
     * @param clock clock used to decide whether a cached schema has expired
     */
    public SchemaCache(SourceRegistry registry,
                       SchemaDiscoveryService discoveryService,
                       SchemaToEntityProperties properties,
                       Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        Duration configured = properties.getSchemaCacheTtl();
        this.ttl = configured == null ? Duration.ofMinutes(10) : configured;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the cached schema for the source, discovering it on first use
     * or when the configured TTL has expired.
     *
     * @throws SourceNotFoundException when the source is not configured
     */
    public SourceSchema get(String source) {
        CachedSchema cached = cache.get(source);
        if (cached != null && !expired(cached)) {
            return cached.schema();
        }
        Object lock = locks.computeIfAbsent(source, key -> new Object());
        synchronized (lock) {
            cached = cache.get(source);
            if (cached != null && !expired(cached)) {
                return cached.schema();
            }
            SourceSchema schema = discover(source);
            cache.put(source, new CachedSchema(schema, clock.instant()));
            return schema;
        }
    }

    /**
     * Forces rediscovery of the source's schema, replacing any cached value.
     *
     * @throws SourceNotFoundException when the source is not configured
     */
    public SourceSchema refresh(String source) {
        Object lock = locks.computeIfAbsent(source, key -> new Object());
        synchronized (lock) {
            SourceSchema schema = discover(source);
            cache.put(source, new CachedSchema(schema, clock.instant()));
            return schema;
        }
    }

    /**
     * Returns one entity from the source's cached schema.
     *
     * @throws SourceNotFoundException   when the source is not configured
     * @throws EntityNotFoundException   when the entity was not discovered
     */
    public EntityModel entity(String source, String entity) {
        return get(source).entity(entity);
    }

    private boolean expired(CachedSchema cached) {
        return !clock.instant().isBefore(cached.discoveredAt().plus(ttl));
    }

    private SourceSchema discover(String source) {
        DataSourceDefinition definition = registry.definition(source);
        try (Connection connection = registry.get(source).getConnection()) {
            Map<String, Object> discovered = discoveryService.inspect(
                    connection, definition.schema(), definition.tables());
            return toSourceSchema(discovered, definition.viewKeys());
        } catch (SQLException e) {
            // Never include the SQLException message: driver errors can carry
            // the JDBC URL or credentials.
            throw new ConnectionException(
                    "Schema discovery failed for source '" + source + "'", e);
        }
    }

    private static SourceSchema toSourceSchema(Map<String, Object> discovered,
                                                Map<String, String> viewKeys) {
        Map<String, EntityModel> entities = new LinkedHashMap<>();
        if (discovered.get("tables") instanceof List<?> tables) {
            for (Object item : tables) {
                if (item instanceof Map<?, ?> table) {
                    EntityModel entity = toEntityModel(asStringKeyMap(table), viewKeys);
                    entities.put(entity.name().toLowerCase(Locale.ROOT), entity);
                }
            }
        }
        String databaseType = stringValue(discovered, "dialect");
        if (databaseType == null) {
            databaseType = stringValue(discovered, "source");
        }
        return new SourceSchema(
                databaseType,
                stringValue(discovered, "catalog"),
                stringValue(discovered, "schema"),
                entities);
    }

    private static EntityModel toEntityModel(Map<String, Object> table,
                                              Map<String, String> viewKeys) {
        String name = (String) table.get("name");
        String kind = (String) table.get("kind");

        List<ColumnModel> columns = new ArrayList<>();
        if (table.get("columns") instanceof List<?> columnValues) {
            for (Object item : columnValues) {
                if (item instanceof Map<?, ?> column) {
                    Map<String, Object> columnMap = asStringKeyMap(column);
                    String columnName = (String) columnMap.get("name");
                    String type = (String) columnMap.get("type");
                    columns.add(new ColumnModel(
                            columnName,
                            type,
                            ColumnCategory.fromTypeName(type),
                            Boolean.TRUE.equals(columnMap.get("nullable"))));
                }
            }
        }

        List<String> keyColumns = "table".equals(kind)
                ? primaryKeyColumns(table.get("primaryKey"))
                : viewKeyColumns(name, columns, viewKeys);
        return new EntityModel(name, kind, columns, keyColumns);
    }

    private static List<String> primaryKeyColumns(Object primaryKey) {
        if (primaryKey instanceof String name) {
            return List.of(name);
        }
        if (primaryKey instanceof List<?> keys) {
            List<String> columns = new ArrayList<>();
            for (Object key : keys) {
                if (key instanceof String name) {
                    columns.add(name);
                }
            }
            return List.copyOf(columns);
        }
        return List.of();
    }

    /**
     * Returns the configured view key when it names a column that the view
     * actually has. The comparison is case-insensitive; a missing column is
     * ignored, leaving the view without a key.
     */
    private static List<String> viewKeyColumns(String viewName,
                                               List<ColumnModel> columns,
                                               Map<String, String> viewKeys) {
        if (viewName == null || viewKeys == null || viewKeys.isEmpty()) {
            return List.of();
        }
        String configured = null;
        for (Map.Entry<String, String> entry : viewKeys.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(viewName)) {
                configured = entry.getValue();
                break;
            }
        }
        final String keyColumn = configured;
        if (keyColumn == null || keyColumn.isBlank()) {
            return List.of();
        }
        boolean exists = columns.stream()
                .anyMatch(column -> column.name().equalsIgnoreCase(keyColumn));
        return exists ? List.of(keyColumn) : List.of();
    }

    private static String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private record CachedSchema(SourceSchema schema, Instant discoveredAt) {
    }
}
