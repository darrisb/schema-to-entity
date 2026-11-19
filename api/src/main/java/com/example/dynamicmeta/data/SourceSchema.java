package com.example.dynamicmeta.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, typed snapshot of one source's discovered schema.
 * <p>
 * Entities are keyed by their lower-case name so lookups are
 * case-insensitive. The map is unmodifiable.
 *
 * @param databaseType dialect identifier, for example {@code h2} or {@code postgresql}
 * @param catalog      discovered catalog, may be null for databases without one
 * @param schema       discovered schema, may be null for databases without one
 * @param entities     discovered tables and views keyed by lower-case name
 */
public record SourceSchema(String databaseType, String catalog, String schema,
                           Map<String, EntityModel> entities) {

    public SourceSchema {
        Objects.requireNonNull(entities, "entities");
        Map<String, EntityModel> byLowerCaseName = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModel> entry : entities.entrySet()) {
            byLowerCaseName.put(
                    Objects.requireNonNull(entry.getKey(), "entity key")
                            .toLowerCase(Locale.ROOT),
                    Objects.requireNonNull(entry.getValue(), "entity"));
        }
        entities = Collections.unmodifiableMap(byLowerCaseName);
    }

    /**
     * Returns the entity with the given name, ignoring case.
     *
     * @throws EntityNotFoundException when no such entity was discovered
     */
    public EntityModel entity(String name) {
        if (name == null) {
            throw new EntityNotFoundException(String.valueOf(name));
        }
        EntityModel entity = entities.get(name.toLowerCase(Locale.ROOT));
        if (entity == null) {
            throw new EntityNotFoundException(name);
        }
        return entity;
    }
}
