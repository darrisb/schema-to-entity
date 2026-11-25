package com.example.dynamicmeta.graph;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The combined schema document: one immutable snapshot describing every
 * configured source in one place, the entities and columns discovered in
 * them, the relationships between them and the overlay ids that no longer
 * match anything.
 * <p>
 * Sources, entities and relationships are identified by the
 * {@link GraphIds} schemes, so each entry can be referenced from anywhere in
 * the document and from the overlay JSON. {@code orphans} lists overlay ids
 * whose source, entity, column or relationship no longer exists. The
 * document describes the sources; nothing in it writes to any source
 * database.
 *
 * @param specVersion   version of the document layout, currently {@code 1.0}
 * @param generatedAt   when the snapshot was taken
 * @param sources       every configured source, available or not
 * @param entities      discovered entities across all sources
 * @param relationships relationships across all sources
 * @param orphans       overlay ids that no longer match anything
 */
public record SchemaGraph(String specVersion, Instant generatedAt, List<GraphSource> sources,
                          List<GraphEntity> entities, List<GraphRelationship> relationships,
                          List<String> orphans) {

    /** Version of the document layout written by this starter. */
    public static final String SPEC_VERSION = "1.0";

    public SchemaGraph {
        specVersion = specVersion == null ? SPEC_VERSION : specVersion;
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        sources = List.copyOf(sources);
        entities = List.copyOf(entities);
        relationships = List.copyOf(relationships);
        orphans = List.copyOf(orphans);
    }

    /**
     * Constructor for callers that always write the current layout; the
     * spec version is {@link #SPEC_VERSION}.
     */
    public SchemaGraph(Instant generatedAt, List<GraphSource> sources,
                       List<GraphEntity> entities, List<GraphRelationship> relationships,
                       List<String> orphans) {
        this(SPEC_VERSION, generatedAt, sources, entities, relationships, orphans);
    }
}
