package com.example.dynamicmeta.pages;

/** One schema dependency found in a visual UI page component. */
public record PageUsage(
        String componentId,
        String kind,
        String entityId,
        String columnId) {
}
