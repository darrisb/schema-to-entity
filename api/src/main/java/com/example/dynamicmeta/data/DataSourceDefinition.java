package com.example.dynamicmeta.data;

import com.example.dynamicmeta.connection.SecretRedactor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, validated definition of one named read-only data source.
 * <p>
 * Instances are created by {@link DataSourceDefinitions} from
 * {@link com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties}.
 * The data API is read-only: it never issues INSERT, UPDATE, DELETE or DDL.
 * <p>
 * {@link #toString()} never prints the password; the URL is passed through
 * {@link SecretRedactor}.
 */
public record DataSourceDefinition(
        String name,
        String url,
        String username,
        String password,
        String schema,
        List<String> tables,
        Boolean ssl,
        int maxPoolSize,
        Map<String, String> viewKeys,
        List<String> hiddenColumns) {

    public DataSourceDefinition {
        tables = tables == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(tables));
        viewKeys = viewKeys == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(viewKeys));
        hiddenColumns = hiddenColumns == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(hiddenColumns));
    }

    /** Definition without hidden columns. */
    public DataSourceDefinition(String name, String url, String username, String password,
                                String schema, List<String> tables, Boolean ssl,
                                int maxPoolSize, Map<String, String> viewKeys) {
        this(name, url, username, password, schema, tables, ssl, maxPoolSize, viewKeys,
                List.of());
    }

    @Override
    public String toString() {
        return "DataSourceDefinition[" +
                "name=" + name +
                ", url=" + (url == null ? "null" : SecretRedactor.redact(url)) +
                ", username=" + username +
                ", password=" + (password == null ? "null" : "***") +
                ", schema=" + schema +
                ", tables=" + tables +
                ", ssl=" + ssl +
                ", maxPoolSize=" + maxPoolSize +
                ", viewKeys=" + viewKeys +
                ", hiddenColumns=" + hiddenColumns + "]";
    }
}
