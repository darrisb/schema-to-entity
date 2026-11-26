package com.example.dynamicmeta.pages;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceSchema;
import com.example.dynamicmeta.graph.GraphIds;

/** Compares page dependencies with the schemas currently served by the cache. */
public final class PageStatusService {

    private final SchemaCache schemaCache;
    private final PageDependencyExtractor dependencyExtractor;

    public PageStatusService(SchemaCache schemaCache,
                             PageDependencyExtractor dependencyExtractor) {
        this.schemaCache = schemaCache;
        this.dependencyExtractor = dependencyExtractor;
    }

    public PageStatus status(Page page) {
        PageDependencies dependencies = dependencyExtractor.extract(page.document());
        Set<String> missingEntities = new LinkedHashSet<>();
        Set<String> missingColumns = new LinkedHashSet<>();
        Set<String> staleSources = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();

        dependencies.entities().forEach(id ->
                sources.add(GraphIds.parseEntityId(id).source()));

        if (schemaCache == null && !sources.isEmpty()) {
            return unavailable(page.id());
        }

        try {
            for (String source : sources) {
                SourceSchema schema = schemaCache.get(source);
                inspectEntities(source, schema, dependencies, missingEntities, missingColumns);
                String recorded = dependencies.schemaVersions().get(source);
                if (recorded != null
                        && !recorded.equalsIgnoreCase(schemaCache.schemaVersion(source))) {
                    staleSources.add(source);
                }
            }
        } catch (RuntimeException unreadableSource) {
            // Source lookup and discovery failures deliberately collapse to a generic status.
            return unavailable(page.id());
        }

        String state = !missingEntities.isEmpty() || !missingColumns.isEmpty()
                ? "broken" : staleSources.isEmpty() ? "ok" : "stale";
        return new PageStatus(page.id(), state, missingEntities, missingColumns, staleSources);
    }

    private static void inspectEntities(String source, SourceSchema schema,
                                        PageDependencies dependencies,
                                        Set<String> missingEntities,
                                        Set<String> missingColumns) {
        dependencies.entities().stream()
                .map(GraphIds::parseEntityId)
                .filter(id -> id.source().equalsIgnoreCase(source))
                .forEach(id -> {
                    EntityModel entity = schema.entities().get(
                            id.entity().toLowerCase(Locale.ROOT));
                    String entityId = GraphIds.entityId(source, id.entity());
                    if (entity == null) {
                        missingEntities.add(entityId);
                        return;
                    }
                    dependencies.columns().stream()
                            .map(GraphIds::parseColumnId)
                            .filter(column -> column.source().equalsIgnoreCase(source)
                                    && column.entity().equalsIgnoreCase(id.entity()))
                            .filter(column -> entity.columns().stream()
                                    .map(ColumnModel::name)
                                    .noneMatch(name -> name.equalsIgnoreCase(column.column())))
                            .map(column -> GraphIds.columnId(
                                    source, id.entity(), column.column()))
                            .forEach(missingColumns::add);
                });
    }

    private static PageStatus unavailable(String pageId) {
        return new PageStatus(pageId, "unavailable", Set.of(), Set.of(), Set.of());
    }
}
