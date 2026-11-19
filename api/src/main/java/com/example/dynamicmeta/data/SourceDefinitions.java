package com.example.dynamicmeta.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable holder for the validated source definitions exposed as a Spring
 * bean.
 * <p>
 * The dedicated type avoids Spring's collection-injection semantics for
 * {@code Map<String, T>}: such a parameter is resolved as "all beans of type
 * {@code T}" whenever any {@code DataSourceDefinition} bean exists, instead of
 * as the definitions built and validated by {@link DataSourceDefinitions}.
 */
public record SourceDefinitions(Map<String, DataSourceDefinition> definitions) {

    public SourceDefinitions {
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }
}
