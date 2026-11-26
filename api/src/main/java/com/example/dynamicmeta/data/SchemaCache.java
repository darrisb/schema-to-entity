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
import com.example.dynamicmeta.drift.SchemaVersion;

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
 * DR-008: each cache entry also carries the source's short schema version (the
 * leading characters of the snapshot fingerprint of the cached schema, see
 * {@link SchemaVersion}). It is computed once per discovery, next to the entry,
 * so {@link #schemaVersion(String)} serves the data API without hashing
 * anything per request, and it changes exactly when the cached schema changes.
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
        return cached(source).schema();
    }

    /**
     * Forces rediscovery of the source's schema, replacing any cached value.
     *
     * @throws SourceNotFoundException when the source is not configured
     */
    public SourceSchema refresh(String source) {
        requireConfigured(source);
        Object lock = locks.computeIfAbsent(source, key -> new Object());
        synchronized (lock) {
            return store(source).schema();
        }
    }

    /**
     * Returns the short schema version of the source's currently cached schema
     * (DR-008), discovering the schema on first use or when the configured TTL
     * has expired. The version is the leading
     * {@value com.example.dynamicmeta.drift.SchemaVersion#LENGTH} characters of
     * the snapshot fingerprint and changes only when the cached schema changes.
     *
     * @throws SourceNotFoundException when the source is not configured
     */
    public String schemaVersion(String source) {
        return cached(source).schemaVersion();
    }

    /**
     * The cache entry for the source, discovering it on first use or when the
     * configured TTL has expired. Concurrent first calls for one source are
     * serialized so the schema is discovered only once.
     */
    private CachedSchema cached(String source) {
        requireConfigured(source);
        CachedSchema cached = cache.get(source);
        if (cached != null && !expired(cached)) {
            return cached;
        }
        Object lock = locks.computeIfAbsent(source, key -> new Object());
        synchronized (lock) {
            cached = cache.get(source);
            if (cached != null && !expired(cached)) {
                return cached;
            }
            return store(source);
        }
    }

    /**
     * Discovers the source and stores its schema and schema version together.
     * The caller holds the source's lock, so the entry can be replaced safely.
     */
    private CachedSchema store(String source) {
        SourceSchema schema = discover(source);
        Instant discoveredAt = clock.instant();
        CachedSchema cached = new CachedSchema(schema, discoveredAt,
                SchemaVersion.of(source, schema, discoveredAt));
        cache.put(source, cached);
        return cached;
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

    /**
     * Rejects unknown names before anything is stored for them, so requests for
     * random source names cannot grow the lock map.
     */
    private void requireConfigured(String source) {
        if (source == null || !registry.names().contains(source)) {
            throw new SourceNotFoundException(source);
        }
    }

    private boolean expired(CachedSchema cached) {
        return !clock.instant().isBefore(cached.discoveredAt().plus(ttl));
    }

    private SourceSchema discover(String source) {
        DataSourceDefinition definition = registry.definition(source);
        try (Connection connection = registry.get(source).getConnection()) {
            Map<String, Object> discovered = discoveryService.inspect(
                    connection, definition.schema(), definition.tables());
            return toSourceSchema(discovered, definition.viewKeys(),
                    definition.hiddenColumns());
        } catch (SQLException e) {
            // Never include the SQLException message: driver errors can carry
            // the JDBC URL or credentials.
            throw new ConnectionException(
                    "Schema discovery failed for source '" + source + "'", e);
        }
    }

    private static SourceSchema toSourceSchema(Map<String, Object> discovered,
                                                Map<String, String> viewKeys,
                                                List<String> hiddenColumns) {
        Map<String, EntityModel> entities = new LinkedHashMap<>();
        if (discovered.get("tables") instanceof List<?> tables) {
            for (Object item : tables) {
                if (item instanceof Map<?, ?> table) {
                    EntityModel entity = toEntityModel(asStringKeyMap(table), viewKeys,
                            hiddenColumns);
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
                                              Map<String, String> viewKeys,
                                              List<String> hiddenColumns) {
        String name = (String) table.get("name");
        String kind = (String) table.get("kind");

        List<ColumnModel> columns = new ArrayList<>();
        if (table.get("columns") instanceof List<?> columnValues) {
            for (Object item : columnValues) {
                if (item instanceof Map<?, ?> column) {
                    Map<String, Object> columnMap = asStringKeyMap(column);
                    String columnName = (String) columnMap.get("name");
                    if (isHidden(name, columnName, hiddenColumns)) {
                        continue;
                    }
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
        // A hidden key column leaves the entity without a usable key, so it can
        // still be listed but not fetched by key.
        boolean keyHidden = keyColumns.stream()
                .anyMatch(key -> isHidden(name, key, hiddenColumns));
        return new EntityModel(name, kind, columns, keyHidden ? List.of() : keyColumns,
                foreignKeys(table, name, hiddenColumns), baseTables(table.get("baseTables")));
    }

    /**
     * Copies the discovered foreign keys into the model, dropping any whose
     * column is hidden: a hidden column is not part of the entity, so its
     * foreign key must not be either. Applies to declared and inferred keys.
     */
    private static List<ForeignKeyModel> foreignKeys(Map<String, Object> table, String entityName,
                                                      List<String> hiddenColumns) {
        if (!(table.get("foreignKeys") instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<ForeignKeyModel> foreignKeys = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> value)) {
                continue;
            }
            Map<String, Object> foreignKey = asStringKeyMap(value);
            String column = (String) foreignKey.get("column");
            if (isHidden(entityName, column, hiddenColumns)) {
                continue;
            }
            foreignKeys.add(new ForeignKeyModel(
                    (String) foreignKey.get("name"),
                    column,
                    (String) foreignKey.get("referencedTable"),
                    (String) foreignKey.get("referencedColumn"),
                    (String) foreignKey.get("origin")));
        }
        return List.copyOf(foreignKeys);
    }

    /** Copies the view dependency names a discovery entry carries; tables have none. */
    private static List<String> baseTables(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<String> baseTables = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String baseTable) {
                baseTables.add(baseTable);
            }
        }
        return List.copyOf(baseTables);
    }

    /**
     * True when the column is configured as hidden, either by bare column name
     * (every entity) or as {@code entity.column}. Matching ignores case.
     */
    static boolean isHidden(String entity, String column, List<String> hiddenColumns) {
        if (column == null || hiddenColumns == null || hiddenColumns.isEmpty()) {
            return false;
        }
        String qualified = entity == null ? null : entity + "." + column;
        for (String hidden : hiddenColumns) {
            if (hidden == null) {
                continue;
            }
            String entry = hidden.trim();
            if (entry.equalsIgnoreCase(column)
                    || (qualified != null && entry.equalsIgnoreCase(qualified))) {
                return true;
            }
        }
        return false;
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

    /**
     * One cached discovery: the schema, when it was found, and the short schema
     * version of that structure (DR-008), computed once here so requests only
     * read it back.
     */
    private record CachedSchema(SourceSchema schema, Instant discoveredAt, String schemaVersion) {
    }
}
