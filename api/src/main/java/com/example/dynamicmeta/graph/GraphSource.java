package com.example.dynamicmeta.graph;

import java.util.Objects;

/**
 * One configured source inside a {@link SchemaGraph}.
 * <p>
 * {@code status} is {@link #OK} when the source's schema was discovered
 * successfully and {@link #UNAVAILABLE} otherwise; any other value is
 * normalized to {@code unavailable}. {@code error} is the reason the source
 * was unavailable and is null for an {@code ok} source. {@code catalog} and
 * {@code schema} may be null for databases without one, as in
 * {@link com.example.dynamicmeta.data.SourceSchema}. Neither the value nor
 * the error message ever contains a credential or a connection string.
 *
 * @param id           source id as configured, for example {@code orders}
 * @param databaseType dialect identifier, for example {@code h2} or {@code postgresql}
 * @param catalog      discovered catalog, possibly null
 * @param schema       discovered schema, possibly null
 * @param status       {@code ok} or {@code unavailable}
 * @param error        why the source is unavailable, null when it is {@code ok}
 */
public record GraphSource(String id, String databaseType, String catalog, String schema,
                          String status, String error) {

    public static final String OK = "ok";
    public static final String UNAVAILABLE = "unavailable";

    public GraphSource {
        id = Objects.requireNonNull(id, "id");
        databaseType = Objects.requireNonNull(databaseType, "databaseType");
        status = OK.equals(status) ? OK : UNAVAILABLE;
    }
}
