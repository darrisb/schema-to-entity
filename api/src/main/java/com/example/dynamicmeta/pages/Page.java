package com.example.dynamicmeta.pages;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/** A visual UI page document and its storage metadata. */
public record Page(
        String id,
        String name,
        String description,
        JsonNode document,
        Instant createdAt,
        Instant updatedAt) {

    public Page {
        if (document == null || !document.isArray()) {
            throw new IllegalArgumentException("page document must be a JSON array");
        }
    }
}
