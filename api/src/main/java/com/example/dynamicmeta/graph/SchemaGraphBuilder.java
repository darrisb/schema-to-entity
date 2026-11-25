package com.example.dynamicmeta.graph;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.ForeignKeyModel;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.data.SourceSchema;

/**
 * Builds a {@link SchemaGraph} from every configured source using
 * {@link SchemaCache}.
 * <p>
 * One source whose discovery fails does not break the document: it is
 * reported as a {@link GraphSource} with status {@code unavailable} and the
 * generic error {@link #DISCOVERY_FAILED} — never a driver message, which
 * could carry a JDBC URL or credentials — while every other source is still
 * included. Sources are requested through the cache, so repeated builds
 * reuse the same discovered schemas.
 * <p>
 * Each discovered entity becomes a {@link GraphEntity}, its columns become
 * {@link GraphColumn}s and each discovered foreign key becomes a
 * {@link GraphRelationship} within that one source. A foreign key whose
 * target entity or target column is not in the document — for example a
 * reference into another schema — is skipped, as is one whose own column was
 * not discovered. After every requested source is built,
 * {@link CrossSourceInference} proposes cross-source relationships from
 * entity and column names alone: no source's metadata knows another's
 * tables, so links between sources can only be proposed, never discovered.
 * Proposals are collected after the within-source relationships — only once
 * every requested source is built can names be matched across sources — and
 * are then deduplicated and sorted by id together with them. They never join
 * two entities of one source; they can be left out entirely by passing
 * {@code false} as the {@code crossSourceInference} constructor flag.
 * Entities, their columns and the relationships are sorted
 * by id, so two builds over the same schemas produce the same document.
 * {@code label}, {@code description} and {@code orphans} are left empty by
 * this builder. The builder only reads metadata; nothing in it writes to any
 * source database.
 */
public class SchemaGraphBuilder {

    /** Dialect reported for a source whose schema could not be discovered. */
    private static final String UNKNOWN_DATABASE_TYPE = "unknown";

    /** Generic reason stored for an unavailable source; never a driver message. */
    private static final String DISCOVERY_FAILED = "Schema discovery failed";

    /** Confidence recorded for a relationship inferred from a view column. */
    private static final double INFERRED_CONFIDENCE = 0.9;

    /** Evidence token naming how an inferred relationship was found. */
    private static final String VIEW_COLUMN_NAME_EVIDENCE = "view-column-name";

    private final SourceRegistry registry;
    private final SchemaCache cache;
    private final Clock clock;
    private final boolean crossSourceInference;

    public SchemaGraphBuilder(SourceRegistry registry, SchemaCache cache) {
        this(registry, cache, Clock.systemUTC());
    }

    /**
     * Constructor used by tests that need to control time.
     *
     * @param clock clock supplying the document's {@code generatedAt}
     */
    public SchemaGraphBuilder(SourceRegistry registry, SchemaCache cache, Clock clock) {
        this(registry, cache, clock, true);
    }

    /**
     * Constructor that also chooses whether cross-source relationships are
     * proposed. Used by tests that need to control time or inference.
     *
     * @param clock                 clock supplying the document's {@code generatedAt}
     * @param crossSourceInference  {@code false} to build the document without
     *                              the proposed cross-source relationships
     */
    public SchemaGraphBuilder(SourceRegistry registry, SchemaCache cache, Clock clock,
                              boolean crossSourceInference) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.crossSourceInference = crossSourceInference;
    }

    /**
     * Builds the combined document from the requested sources.
     *
     * @param sourceNames sources to include; null or empty selects every
     *                    configured source
     * @return one snapshot of every requested source, available or not
     */
    public SchemaGraph build(Set<String> sourceNames) {
        Set<String> requested = sourceNames == null || sourceNames.isEmpty()
                ? registry.names()
                : sourceNames;
        List<String> names = requested.stream().filter(Objects::nonNull).sorted().toList();

        List<GraphSource> sources = new ArrayList<>();
        List<GraphEntity> entities = new ArrayList<>();
        List<GraphRelationship> relationships = new ArrayList<>();

        for (String name : names) {
            try {
                SourceSchema schema = cache.get(name);
                BuiltSource built = buildSource(name, schema);
                sources.add(built.source());
                entities.addAll(built.entities());
                relationships.addAll(built.relationships());
            } catch (RuntimeException e) {
                // Discovery failed for this source only, so the document keeps
                // going. The stored reason is generic: driver messages can
                // carry the JDBC URL or credentials.
                sources.add(new GraphSource(name, UNKNOWN_DATABASE_TYPE, null, null,
                        GraphSource.UNAVAILABLE, DISCOVERY_FAILED));
            }
        }

        // Cross-source proposals come after the within-source relationships:
        // only now that every requested source is built can names be matched
        // across sources. Inference only reads the entities already collected
        // and adds proposed relationships, never declared ones, and never a
        // link inside one source.
        if (crossSourceInference) {
            relationships.addAll(CrossSourceInference.propose(entities));
        }

        Map<String, GraphRelationship> unique = new LinkedHashMap<>();
        for (GraphRelationship relationship : relationships) {
            unique.putIfAbsent(relationship.id(), relationship);
        }

        sources.sort(Comparator.comparing(GraphSource::id));
        entities.sort(Comparator.comparing(GraphEntity::id));
        List<GraphRelationship> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparing(GraphRelationship::id));

        return new SchemaGraph(clock.instant(), sources, entities, ordered, List.of());
    }

    /**
     * Turns one discovered schema into the graph pieces for that source: the
     * available {@link GraphSource}, its entities and its within-source
     * relationships.
     */
    private BuiltSource buildSource(String source, SourceSchema schema) {
        List<GraphEntity> entities = new ArrayList<>();
        List<GraphRelationship> relationships = new ArrayList<>();
        for (EntityModel model : schema.entities().values()) {
            entities.add(toGraphEntity(source, model));
            for (ForeignKeyModel foreignKey : model.foreignKeys()) {
                GraphRelationship relationship = toRelationship(source, schema, model, foreignKey);
                if (relationship != null) {
                    relationships.add(relationship);
                }
            }
        }
        String databaseType = schema.databaseType() == null
                ? UNKNOWN_DATABASE_TYPE
                : schema.databaseType();
        return new BuiltSource(
                new GraphSource(source, databaseType, schema.catalog(), schema.schema(),
                        GraphSource.OK, null),
                entities, relationships);
    }

    private static GraphEntity toGraphEntity(String source, EntityModel model) {
        List<GraphColumn> columns = new ArrayList<>();
        for (ColumnModel column : model.columns()) {
            columns.add(new GraphColumn(
                    GraphIds.columnId(source, model.name(), column.name()),
                    column.name(), column.type(), column.category(), column.nullable(),
                    null, null));
        }
        columns.sort(Comparator.comparing(GraphColumn::id));

        // A view's base tables are recorded as the entity ids they name in
        // the same source.
        List<String> baseTables = new ArrayList<>();
        for (String baseTable : model.baseTables()) {
            baseTables.add(GraphIds.entityId(source, baseTable));
        }

        return new GraphEntity(GraphIds.entityId(source, model.name()), source, model.name(),
                model.kind(), null, null, model.keyColumns(), columns, baseTables);
    }

    /**
     * Builds the within-source relationship for one discovered foreign key,
     * or null when the foreign key's own column, target entity or target
     * column is not in the document (for example a reference into another
     * schema). A declared foreign key keeps status {@code declared} with no
     * confidence; one inferred through a view becomes a {@code proposed}
     * relationship at 0.9 confidence with the fixed evidence token
     * {@code "view-column-name"}.
     */
    private GraphRelationship toRelationship(String source, SourceSchema schema,
                                              EntityModel model, ForeignKeyModel foreignKey) {
        ColumnModel fromColumn = findColumn(model, foreignKey.column());
        EntityModel target = foreignKey.referencedTable() == null
                ? null
                : schema.entities().get(foreignKey.referencedTable().toLowerCase(Locale.ROOT));
        ColumnModel toColumn = target == null
                ? null
                : findColumn(target, foreignKey.referencedColumn());
        if (fromColumn == null || toColumn == null) {
            return null;
        }
        String from = GraphIds.columnId(source, model.name(), fromColumn.name());
        String to = GraphIds.columnId(source, target.name(), toColumn.name());
        boolean inferred = ForeignKeyModel.INFERRED.equals(foreignKey.origin());
        return new GraphRelationship(
                GraphIds.relationshipId(from, to),
                from, to,
                inferred ? GraphRelationship.INFERRED : GraphRelationship.DECLARED,
                GraphRelationship.WITHIN_SOURCE,
                inferred ? GraphRelationship.PROPOSED : GraphRelationship.DECLARED,
                inferred ? INFERRED_CONFIDENCE : null,
                inferred ? List.of(VIEW_COLUMN_NAME_EVIDENCE) : List.of());
    }

    /** Finds a column of the entity by name, ignoring case. */
    private static ColumnModel findColumn(EntityModel entity, String name) {
        if (name == null) {
            return null;
        }
        for (ColumnModel column : entity.columns()) {
            if (column.name() != null && column.name().equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    /** The graph pieces built from one successfully discovered source. */
    private record BuiltSource(GraphSource source, List<GraphEntity> entities,
                               List<GraphRelationship> relationships) {
    }
}
