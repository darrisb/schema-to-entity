package com.example.dynamicmeta.drift;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** A saved page and the components in it that use a changed schema object. */
public record PageImpact(String pageId, List<String> componentIds) {

    public PageImpact {
        pageId = Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(componentIds, "componentIds");
        TreeSet<String> sorted = new TreeSet<>();
        for (String componentId : componentIds) {
            sorted.add(Objects.requireNonNull(componentId,
                    "componentIds must not contain null"));
        }
        componentIds = List.copyOf(sorted);
    }
}
