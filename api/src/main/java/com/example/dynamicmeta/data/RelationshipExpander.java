package com.example.dynamicmeta.data;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.example.dynamicmeta.graph.GraphIds;
import com.example.dynamicmeta.graph.GraphRelationship;
import com.example.dynamicmeta.graph.OverlayApplier;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.graph.SchemaGraph;
import com.example.dynamicmeta.graph.SchemaGraphBuilder;

/** Resolves list expansions exclusively from the current overlay-applied graph. */
public final class RelationshipExpander {

    private final SchemaGraphBuilder graphBuilder;
    private final OverlayStore overlayStore;

    public RelationshipExpander(SchemaGraphBuilder graphBuilder, OverlayStore overlayStore) {
        this.graphBuilder = Objects.requireNonNull(graphBuilder, "graphBuilder");
        this.overlayStore = Objects.requireNonNull(overlayStore, "overlayStore");
    }

    public Expansion resolve(String source, String entity, String column) {
        String requested = GraphIds.columnId(source, entity, column);
        SchemaGraph graph = OverlayApplier.apply(graphBuilder.build(graphBuilder.sourceNames()),
                overlayStore.overlay(), false);
        for (GraphRelationship relationship : graph.relationships()) {
            if (requested.equals(relationship.from()) && usable(relationship)) {
                GraphIds.ColumnId target = GraphIds.parseColumnId(relationship.to());
                return new Expansion(column, target.source(), target.entity(), target.column());
            }
        }
        throw new IllegalArgumentException("No usable relationship for " + column);
    }

    private static boolean usable(GraphRelationship relationship) {
        return GraphRelationship.DECLARED.equals(relationship.status())
                || GraphRelationship.CONFIRMED.equals(relationship.status());
    }

    public record Expansion(String requestedColumn, String targetSource,
                            String targetEntity, String targetColumn) {
        public Expansion {
            requestedColumn = requestedColumn.toLowerCase(Locale.ROOT);
        }
    }
}
