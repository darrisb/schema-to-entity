package com.example.dynamicmeta.pages;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Schema dependencies extracted from one visual UI page document. */
public record PageDependencies(List<PageUsage> usages, Map<String, String> schemaVersions) {

    public PageDependencies {
        usages = List.copyOf(usages);
        schemaVersions = Map.copyOf(schemaVersions);
    }

    public Set<String> entities() {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        usages.forEach(usage -> entities.add(usage.entityId()));
        return Set.copyOf(entities);
    }

    public Set<String> columns() {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        usages.stream().map(PageUsage::columnId)
                .filter(column -> column != null && !column.isBlank())
                .forEach(columns::add);
        return Set.copyOf(columns);
    }

    public Set<String> usagesOf(String schemaId) {
        LinkedHashSet<String> componentIds = new LinkedHashSet<>();
        usages.stream()
                .filter(usage -> schemaId != null
                        && (schemaId.equals(usage.entityId()) || schemaId.equals(usage.columnId())))
                .map(PageUsage::componentId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(componentIds::add);
        return Set.copyOf(componentIds);
    }
}
