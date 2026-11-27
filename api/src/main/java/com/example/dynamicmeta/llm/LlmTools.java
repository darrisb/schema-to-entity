package com.example.dynamicmeta.llm;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.dynamicmeta.connection.ConnectionException;
import com.example.dynamicmeta.connection.SecretRedactor;
import com.example.dynamicmeta.data.DataQueryService;
import com.example.dynamicmeta.data.EntityNotFoundException;
import com.example.dynamicmeta.data.SourceNotFoundException;
import com.example.dynamicmeta.graph.GraphColumn;
import com.example.dynamicmeta.graph.GraphEntity;
import com.example.dynamicmeta.graph.GraphIds;
import com.example.dynamicmeta.graph.GraphRelationship;
import com.example.dynamicmeta.graph.GraphSource;
import com.example.dynamicmeta.graph.OverlayApplier;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraph;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;

/**
 * Small, framework-neutral operations intended for function calling and MCP adapters.
 * All data reads delegate to {@link DataQueryService}, retaining its cached-schema
 * validation, generated SELECT statements, and bound parameters.
 */
public final class LlmTools {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_TEXT_LENGTH = 500;

    private final SchemaGraphBuilder graphBuilder;
    private final OverlayStore overlayStore;
    private final DataQueryService queryService;

    public LlmTools(SchemaGraphBuilder graphBuilder, OverlayStore overlayStore,
                    DataQueryService queryService) {
        this.graphBuilder = Objects.requireNonNull(graphBuilder, "graphBuilder");
        this.overlayStore = Objects.requireNonNull(overlayStore, "overlayStore");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
    }

    public List<Map<String, Object>> listSources() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GraphSource source : graph().sources()) {
            result.add(map("name", source.id(), "databaseType", source.databaseType(),
                    "status", source.status()));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> describeEntity(String entityId) {
        try {
            SchemaGraph graph = graph();
            GraphEntity entity = entity(graph, entityId);
            List<Map<String, Object>> columns = new ArrayList<>();
            for (GraphColumn column : entity.columns()) {
                columns.add(map("name", column.name(), "category", column.category().name(),
                        "key", containsIgnoreCase(entity.keyColumns(), column.name()),
                        "label", column.label(), "description", column.description()));
            }
            return truncate(map("id", entity.id(), "kind", entity.kind(),
                    "label", entity.label(), "description", entity.description(),
                    "columns", columns, "relationships", relationships(graph, entity)));
        } catch (RuntimeException exception) {
            return error(exception);
        }
    }

    public Object findRelated(String entityId) {
        try {
            SchemaGraph graph = graph();
            GraphEntity entity = entity(graph, entityId);
            return truncate(relationships(graph, entity));
        } catch (RuntimeException exception) {
            return error(exception);
        }
    }

    public Map<String, Object> queryEntity(String entityId, Map<String, ?> filters,
                                           List<String> fields, List<String> sort,
                                           Integer limit, List<String> expand) {
        try {
            GraphIds.EntityId id = resolvedId(entityId);
            Map<String, List<String>> parameters = parameters(filters);
            addCsv(parameters, "fields", fields);
            addCsv(parameters, "sort", sort);
            parameters.put("limit", List.of(String.valueOf(limit(limit))));
            DataQueryService.DataPage page = queryService.query(id.source(), id.entity(),
                    parameters, true, expand == null ? List.of() : expand);
            return truncate(map("items", page.items(), "total", page.total(),
                    "limit", page.limit(), "offset", page.offset()));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    public Map<String, Object> aggregate(String entityId, List<String> group,
                                         List<String> metrics, Map<String, ?> filters,
                                         List<String> sort, Integer limit) {
        try {
            GraphIds.EntityId id = resolvedId(entityId);
            Map<String, List<String>> parameters = parameters(filters);
            addCsv(parameters, "group", group);
            addCsv(parameters, "metric", metrics);
            addCsv(parameters, "sort", sort);
            parameters.put("limit", List.of(String.valueOf(limit(limit))));
            DataQueryService.AggregateResult result =
                    queryService.aggregate(id.source(), id.entity(), parameters);
            return truncate(map("groups", result.groups(), "metrics", result.metrics(),
                    "rows", result.rows()));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private GraphIds.EntityId resolvedId(String entityId) {
        GraphIds.EntityId id = GraphIds.parseEntityId(entityId);
        // Resolve through the overlay-applied graph first so malformed and unknown graph
        // ids have exactly the same error behavior as the descriptive operations.
        entity(graph(), entityId);
        return id;
    }

    private SchemaGraph graph() {
        return OverlayApplier.apply(graphBuilder.build(graphBuilder.sourceNames()),
                overlayStore.overlay(), false);
    }

    private static GraphEntity entity(SchemaGraph graph, String requested) {
        final String normalized;
        try {
            GraphIds.EntityId id = GraphIds.parseEntityId(requested);
            normalized = GraphIds.entityId(id.source(), id.entity());
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("entityId must look like source:entity");
        }
        return graph.entities().stream().filter(value -> value.id().equals(normalized))
                .findFirst().orElseThrow(() -> new EntityNotFoundException(normalized));
    }

    private static List<Map<String, Object>> relationships(SchemaGraph graph,
                                                            GraphEntity entity) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GraphRelationship relationship : graph.relationships()) {
            if (!usable(relationship)) continue;
            GraphIds.ColumnId from = GraphIds.parseColumnId(relationship.from());
            GraphIds.ColumnId to = GraphIds.parseColumnId(relationship.to());
            String fromEntity = GraphIds.entityId(from.source(), from.entity());
            String toEntity = GraphIds.entityId(to.source(), to.entity());
            if (entity.id().equals(fromEntity)) {
                result.add(map("entityId", toEntity, "direction", "outgoing",
                        "fromEntityId", fromEntity, "fromColumn", from.column(),
                        "toEntityId", toEntity, "toColumn", to.column(),
                        "status", relationship.status()));
            } else if (entity.id().equals(toEntity)) {
                result.add(map("entityId", fromEntity, "direction", "incoming",
                        "fromEntityId", fromEntity, "fromColumn", from.column(),
                        "toEntityId", toEntity, "toColumn", to.column(),
                        "status", relationship.status()));
            }
        }
        return List.copyOf(result);
    }

    private static boolean usable(GraphRelationship relationship) {
        return GraphRelationship.DECLARED.equals(relationship.status())
                || GraphRelationship.CONFIRMED.equals(relationship.status());
    }

    private static Map<String, List<String>> parameters(Map<String, ?> values) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (values == null) return result;
        values.forEach((key, value) -> {
            if (value instanceof Iterable<?> iterable) {
                List<String> strings = new ArrayList<>();
                iterable.forEach(item -> strings.add(String.valueOf(item)));
                result.put(key, List.copyOf(strings));
            } else {
                result.put(key, List.of(String.valueOf(value)));
            }
        });
        return result;
    }

    private static void addCsv(Map<String, List<String>> parameters, String name,
                               List<String> values) {
        if (values != null && !values.isEmpty()) parameters.put(name, List.of(String.join(",", values)));
    }

    private static int limit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }

    private static boolean containsIgnoreCase(List<String> values, String requested) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(requested));
    }

    private static Map<String, Object> error(Exception exception) {
        String message;
        if (exception instanceof SQLTimeoutException) message = "Database query timed out";
        else if (exception instanceof SQLTransientConnectionException) {
            message = "Data source is busy; try again shortly";
        } else if (exception instanceof SQLException) message = "Database query failed";
        else if (exception instanceof ConnectionException) message = "Data source is unavailable";
        else if (exception instanceof SourceNotFoundException
                || exception instanceof EntityNotFoundException
                || exception instanceof IllegalArgumentException) message = exception.getMessage();
        else message = "Database query failed";
        return Map.of("error", SecretRedactor.redact(message));
    }

    @SuppressWarnings("unchecked")
    private static <T> T truncate(T value) {
        return (T) truncateValue(value);
    }

    private static Object truncateValue(Object value) {
        if (value instanceof String text) {
            return text.length() <= MAX_TEXT_LENGTH ? text
                    : text.substring(0, MAX_TEXT_LENGTH - 1) + "…";
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(String.valueOf(key), truncateValue(item)));
            return copy;
        }
        if (value instanceof List<?> source) return source.stream().map(LlmTools::truncateValue).toList();
        return value;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
