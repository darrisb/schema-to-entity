package com.example.dynamicmeta.pages;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.graph.GraphIds;
import com.fasterxml.jackson.databind.JsonNode;

/** Extracts schema dependencies from visual UI page documents. */
public final class PageDependencyExtractor {

    public Extraction extract(JsonNode document) {
        List<PageUsage> usages = new ArrayList<>();
        Map<String, String> schemaVersions = new LinkedHashMap<>();
        if (document != null && document.isArray()) {
            document.forEach(component -> walk(component, usages, schemaVersions));
        }
        return new Extraction(usages, schemaVersions);
    }

    private void walk(JsonNode component, List<PageUsage> usages,
                      Map<String, String> schemaVersions) {
        if (component == null || !component.isObject()) {
            return;
        }
        extractDataSource(component, usages, schemaVersions);
        JsonNode children = component.get("children");
        if (children != null && children.isArray()) {
            children.forEach(child -> walk(child, usages, schemaVersions));
        }
    }

    private void extractDataSource(JsonNode component, List<PageUsage> usages,
                                   Map<String, String> schemaVersions) {
        JsonNode dataSource = component.get("dataSource");
        if (dataSource == null || !dataSource.isObject()
                || !"rest".equalsIgnoreCase(text(dataSource, "type"))) {
            return;
        }
        DataPath path = dataPath(text(dataSource, "url"));
        if (path == null) {
            return;
        }

        String componentId = text(component, "id");
        String entityId;
        try {
            entityId = GraphIds.entityId(path.source(), path.entity());
        } catch (RuntimeException ignored) {
            return;
        }
        usages.add(new PageUsage(componentId, "data-source", entityId, null));

        JsonNode mapping = dataSource.get("mapping");
        if (mapping != null && mapping.isObject()) {
            mapping.elements().forEachRemaining(value -> addColumnUsage(
                    usages, componentId, "mapping", path, value.isTextual() ? value.textValue() : null));
        }

        JsonNode query = dataSource.get("query");
        if (query != null && query.isObject()) {
            addFields(usages, componentId, path, query.get("fields"));
            query.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (key.startsWith("eq.") && key.length() > 3) {
                    addColumnUsage(usages, componentId, "query-field", path, key.substring(3));
                } else if (key.startsWith("contains.") && key.length() > 9) {
                    addColumnUsage(usages, componentId, "query-field", path, key.substring(9));
                } else if ("sort".equals(key) && entry.getValue().isTextual()) {
                    for (String sort : entry.getValue().textValue().split(",")) {
                        String column = sort.strip();
                        if (column.startsWith("-") || column.startsWith("+")) {
                            column = column.substring(1);
                        }
                        addColumnUsage(usages, componentId, "query-field", path, column);
                    }
                }
            });
        }

        String version = text(dataSource, "schemaVersion");
        if (version != null && !version.isBlank()) {
            schemaVersions.put(path.source().toLowerCase(java.util.Locale.ROOT), version);
            usages.add(new PageUsage(componentId, "schema-version", entityId, null));
        }
    }

    private void addFields(List<PageUsage> usages, String componentId, DataPath path,
                           JsonNode fields) {
        if (fields == null || !fields.isTextual()) {
            return;
        }
        for (String field : fields.textValue().split(",")) {
            addColumnUsage(usages, componentId, "query-field", path, field);
        }
    }

    private void addColumnUsage(List<PageUsage> usages, String componentId, String kind,
                                DataPath path, String column) {
        if (column == null || column.isBlank()) {
            return;
        }
        try {
            usages.add(new PageUsage(componentId, kind,
                    GraphIds.entityId(path.source(), path.entity()),
                    GraphIds.columnId(path.source(), path.entity(), column)));
        } catch (RuntimeException ignored) {
            // A malformed UI field is not a dependency and must not break analysis.
        }
    }

    private DataPath dataPath(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return null;
            }
            String[] parts = path.split("/");
            for (int i = 0; i + 3 < parts.length; i++) {
                if ("api".equals(parts[i]) && "data".equals(parts[i + 1])
                        && !parts[i + 2].isBlank() && !parts[i + 3].isBlank()) {
                    return new DataPath(parts[i + 2], parts[i + 3]);
                }
            }
        } catch (RuntimeException ignored) {
            // Unexpected URLs are outside the data API dependency format.
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    public record Extraction(List<PageUsage> usages, Map<String, String> schemaVersions) {
        public Extraction {
            usages = List.copyOf(usages);
            schemaVersions = Map.copyOf(schemaVersions);
        }
    }

    private record DataPath(String source, String entity) {
    }
}
