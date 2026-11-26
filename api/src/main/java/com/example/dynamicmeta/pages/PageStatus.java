package com.example.dynamicmeta.pages;

import java.util.Set;

/** Current compatibility of a stored page with the schemas it uses. */
public record PageStatus(
        String pageId,
        String state,
        Set<String> missingEntities,
        Set<String> missingColumns,
        Set<String> staleSources) {

    public PageStatus {
        missingEntities = Set.copyOf(missingEntities);
        missingColumns = Set.copyOf(missingColumns);
        staleSources = Set.copyOf(staleSources);
    }
}
