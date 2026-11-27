package com.example.dynamicmeta.llm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.connection.ConnectionException;
import com.example.dynamicmeta.connection.DatabaseType;
import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SelectBuilder;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.data.SourceSchema;
import com.example.dynamicmeta.data.SqlStatement;
import com.example.dynamicmeta.graph.GraphColumn;
import com.example.dynamicmeta.graph.GraphEntity;
import com.example.dynamicmeta.graph.GraphRelationship;
import com.example.dynamicmeta.graph.OverlayApplier;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraph;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;

/** Builds the compact, read-only schema description exposed under /api/llm. */
public final class SchemaSummary {

    private static final int MAX_VALUES = 20;

    private final SchemaGraphBuilder graphBuilder;
    private final OverlayStore overlayStore;
    private final SchemaCache schemaCache;
    private final SourceRegistry registry;
    private final Duration queryTimeout;
    private final Map<ValueCacheKey, List<String>> valueCache = new ConcurrentHashMap<>();

    public SchemaSummary(SchemaGraphBuilder graphBuilder, OverlayStore overlayStore,
                         SchemaCache schemaCache, SourceRegistry registry,
                         SchemaToEntityProperties properties) {
        this.graphBuilder = Objects.requireNonNull(graphBuilder, "graphBuilder");
        this.overlayStore = Objects.requireNonNull(overlayStore, "overlayStore");
        this.schemaCache = Objects.requireNonNull(schemaCache, "schemaCache");
        this.registry = Objects.requireNonNull(registry, "registry");
        Duration configured = Objects.requireNonNull(properties, "properties").getQueryTimeout();
        this.queryTimeout = configured == null ? Duration.ZERO : configured;
    }

    public Document summarize(Set<String> sources, boolean includeValues) {
        if (sources != null) {
            for (String source : sources) {
                if (!graphBuilder.sourceNames().contains(source)) {
                    throw new com.example.dynamicmeta.data.SourceNotFoundException(source);
                }
            }
        }
        SchemaGraph graph = OverlayApplier.apply(graphBuilder.build(sources),
                overlayStore.overlay(), false);
        List<Entity> entities = new ArrayList<>();
        for (GraphEntity entity : graph.entities()) {
            List<Column> columns = new ArrayList<>();
            for (GraphColumn column : entity.columns()) {
                List<String> values = includeValues && column.category() == ColumnCategory.TEXT
                        ? values(entity, column) : List.of();
                columns.add(new Column(column.name(), column.category().name(),
                        column.label(), column.description(), values));
            }
            List<String> links = links(entity, graph.relationships());
            entities.add(new Entity(entity.id(), entity.kind(), entity.keyColumns(),
                    entity.label(), entity.description(), columns, links));
        }
        entities.sort(Comparator.comparing(Entity::id));
        return new Document(entities);
    }

    public String text(Document document) {
        StringBuilder text = new StringBuilder();
        for (Entity entity : document.entities()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(entity.id()).append(" (").append(entity.kind());
            if (!entity.keyColumns().isEmpty()) {
                text.append(", key ").append(String.join(",", entity.keyColumns()));
            }
            text.append(')');
            String description = display(entity.label(), entity.description());
            if (description != null) {
                text.append(" - ").append(description);
            }
            text.append("\ncolumns: ");
            for (int i = 0; i < entity.columns().size(); i++) {
                if (i > 0) text.append(", ");
                Column column = entity.columns().get(i);
                text.append(column.name()).append(' ').append(column.type());
                String columnDescription = display(column.label(), column.description());
                if (columnDescription != null) text.append(" (").append(columnDescription).append(')');
                if (!column.values().isEmpty()) {
                    text.append(" [values: ").append(String.join(" | ", column.values())).append(']');
                }
            }
            if (!entity.links().isEmpty()) {
                text.append("\nlinks: ").append(String.join(", ", entity.links()));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private List<String> values(GraphEntity graphEntity, GraphColumn graphColumn) {
        SourceSchema schema = schemaCache.get(graphEntity.source());
        String version = schemaCache.schemaVersion(graphEntity.source());
        ValueCacheKey key = new ValueCacheKey(graphEntity.source(), version,
                graphEntity.name(), graphColumn.name());
        return valueCache.computeIfAbsent(key, ignored -> readValues(schema, graphEntity, graphColumn));
    }

    private List<String> readValues(SourceSchema schema, GraphEntity graphEntity,
                                    GraphColumn graphColumn) {
        EntityModel entity = schema.entity(graphEntity.name());
        ColumnModel column = entity.columns().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(graphColumn.name()))
                .findFirst().orElseThrow();
        DatabaseType type = DatabaseType.fromId(schema.databaseType());
        if (type == null) throw new IllegalArgumentException("Unsupported database type");
        SqlStatement select = new SelectBuilder(type, schema.catalog(), schema.schema())
                .selectDistinct(entity, column.name(), MAX_VALUES + 1);
        List<String> values = new ArrayList<>();
        try (Connection connection = registry.get(graphEntity.source()).getConnection();
             PreparedStatement statement = connection.prepareStatement(select.sql())) {
            for (int i = 0; i < select.parameters().size(); i++) {
                statement.setObject(i + 1, select.parameters().get(i));
            }
            statement.setMaxRows(MAX_VALUES + 1);
            statement.setQueryTimeout(timeoutSeconds());
            try (ResultSet result = statement.executeQuery()) {
                int rows = 0;
                while (result.next()) {
                    rows++;
                    if (rows > MAX_VALUES) return List.of();
                    Object value = result.getObject(1);
                    if (value != null) values.add(String.valueOf(value));
                }
            }
        } catch (SQLException exception) {
            throw new ConnectionException("Distinct value lookup failed", exception);
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }

    private int timeoutSeconds() {
        return queryTimeout.isZero() || queryTimeout.isNegative() ? 0
                : (int) Math.max(1L, Math.ceilDiv(queryTimeout.toMillis(), 1000L));
    }

    private static List<String> links(GraphEntity entity, List<GraphRelationship> relationships) {
        List<String> links = new ArrayList<>();
        String prefix = entity.id() + ".";
        for (GraphRelationship relationship : relationships) {
            if (!GraphRelationship.DECLARED.equals(relationship.status())
                    && !GraphRelationship.CONFIRMED.equals(relationship.status())) continue;
            if (relationship.to().startsWith(prefix)) {
                links.add(shortColumn(relationship.to()) + " <- " + relationship.from());
            } else if (relationship.from().startsWith(prefix)) {
                links.add(shortColumn(relationship.from()) + " -> " + relationship.to());
            }
        }
        links.sort(String::compareTo);
        return List.copyOf(links);
    }

    private static String shortColumn(String id) {
        return id.substring(id.lastIndexOf('.') + 1);
    }

    private static String display(String label, String description) {
        if (label == null) return description;
        return description == null ? label : label + ": " + description;
    }

    public record Document(List<Entity> entities) {
        public Document { entities = List.copyOf(entities); }
    }

    public record Entity(String id, String kind, List<String> keyColumns, String label,
                         String description, List<Column> columns, List<String> links) {
        public Entity { keyColumns = List.copyOf(keyColumns); columns = List.copyOf(columns); links = List.copyOf(links); }
    }

    public record Column(String name, String type, String label, String description,
                         List<String> values) {
        public Column { values = List.copyOf(values); }
    }

    private record ValueCacheKey(String source, String version, String entity, String column) { }
}
