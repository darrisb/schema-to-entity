package com.example.dynamicmeta.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merges an {@link Overlay} — the edits a person has made — into a built
 * {@link SchemaGraph}. Pure: {@link #apply} reads the document and the
 * overlay and returns a new document, changing neither. Nothing here ever
 * writes to a source database, and nothing here deletes from the overlay
 * either: an edit whose id matches nothing in the document is reported in
 * the result's {@code orphans} and stays in the overlay, so a later
 * rediscovery that brings the table, column or relationship back reattaches
 * the edit instead of losing it.
 * <p>
 * What applying does:
 * <ul>
 * <li>Labels and descriptions attach to the entity or column whose id
 *     matches the overlay entry.</li>
 * <li>A decision moves a {@code proposed} relationship to {@code confirmed}
 *     or {@code rejected}. A decision on a {@code declared} relationship —
 *     a foreign key the database itself asserts — is ignored: those need no
 *     review and a person cannot unsay them from the overlay.</li>
 * <li>Manual relationships are added as {@code manual}, {@code confirmed}
 *     relationships with no confidence and no evidence, their scope set by
 *     comparing the sources of the two ends. One whose relationship id the
 *     document already has — the same pair as a discovered foreign key, or
 *     a rejected proposal — is not added twice.</li>
 * <li>Every {@code rejected} relationship is removed from the result unless
 *     {@code includeRejected} is true.</li>
 * <li>{@code orphans} lists, sorted, every overlay id — label, decision or
 *     manual relationship — that matches nothing in the document. A manual
 *     relationship one of whose columns no longer exists matches nothing
 *     and is skipped as an orphan.</li>
 * </ul>
 * Entities keep their order; relationships and orphans are sorted by id, so
 * the same graph and overlay always produce the same result.
 */
public final class OverlayApplier {

    private OverlayApplier() {
    }

    /**
     * Applies the overlay to the graph and returns the merged document.
     *
     * @param graph            the built document, as {@link SchemaGraphBuilder}
     *                         produced it
     * @param overlay          the edits to merge in, left untouched
     * @param includeRejected  {@code true} to keep {@code rejected}
     *                         relationships in the result instead of removing
     *                         them
     * @return a new document carrying the overlay's edits and its orphans
     */
    public static SchemaGraph apply(SchemaGraph graph, Overlay overlay, boolean includeRejected) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(overlay, "overlay");

        Set<String> matchedLabelIds = new HashSet<>();
        List<GraphEntity> entities = labelEntities(graph.entities(), overlay.labels(),
                matchedLabelIds);

        // Every relationship id the document had, including ones a decision is
        // about to reject: matching one of these is what makes an overlay id
        // non-orphan, even when the relationship does not survive the merge.
        Set<String> relationshipIds = new HashSet<>();
        for (GraphRelationship relationship : graph.relationships()) {
            relationshipIds.add(relationship.id());
        }

        List<GraphRelationship> relationships = new ArrayList<>();
        for (GraphRelationship relationship : graph.relationships()) {
            relationships.add(decided(relationship, overlay.relationshipDecisions()));
        }

        // Manual relationships join last, after the decisions: a person's
        // rejection of a pair wins over re-asserting the same pair by hand.
        Set<String> manualOrphans = new TreeSet<>();
        relationships.addAll(manualRelationships(overlay, entities, relationshipIds,
                manualOrphans));

        relationships.sort(Comparator.comparing(GraphRelationship::id));
        List<GraphRelationship> kept = new ArrayList<>(relationships.size());
        for (GraphRelationship relationship : relationships) {
            if (includeRejected || !GraphRelationship.REJECTED.equals(relationship.status())) {
                kept.add(relationship);
            }
        }

        return new SchemaGraph(graph.specVersion(), graph.generatedAt(), graph.sources(),
                entities, kept, orphans(overlay, matchedLabelIds, relationshipIds,
                manualOrphans));
    }

    /**
     * Returns the entities with every matching label and description
     * attached, recording which label ids matched. Entities and columns the
     * overlay says nothing about are passed through unchanged.
     */
    private static List<GraphEntity> labelEntities(List<GraphEntity> entities,
                                                    Map<String, Overlay.Label> labels,
                                                    Set<String> matchedLabelIds) {
        List<GraphEntity> labeled = new ArrayList<>(entities.size());
        for (GraphEntity entity : entities) {
            labeled.add(labelEntity(entity, labels, matchedLabelIds));
        }
        return labeled;
    }

    private static GraphEntity labelEntity(GraphEntity entity,
                                            Map<String, Overlay.Label> labels,
                                            Set<String> matchedLabelIds) {
        Overlay.Label entityLabel = labels.get(entity.id());
        if (entityLabel != null) {
            matchedLabelIds.add(entity.id());
        }
        List<GraphColumn> columns = new ArrayList<>(entity.columns().size());
        boolean changed = entityLabel != null;
        for (GraphColumn column : entity.columns()) {
            Overlay.Label columnLabel = labels.get(column.id());
            if (columnLabel == null) {
                columns.add(column);
                continue;
            }
            matchedLabelIds.add(column.id());
            changed = true;
            columns.add(new GraphColumn(column.id(), column.name(), column.type(),
                    column.category(), column.nullable(), columnLabel.label(),
                    columnLabel.description()));
        }
        if (!changed) {
            return entity;
        }
        return new GraphEntity(entity.id(), entity.source(), entity.name(), entity.kind(),
                entityLabel == null ? entity.label() : entityLabel.label(),
                entityLabel == null ? entity.description() : entityLabel.description(),
                entity.keyColumns(), columns, entity.baseTables());
    }

    /**
     * Returns the relationship with the overlay's decision for it applied, or
     * the relationship unchanged when there is no decision or the
     * relationship is not up for review. A decision whose id names any
     * relationship of the document — even a declared one, even one this
     * decision rejects — has matched, so its id is never an orphan.
     */
    private static GraphRelationship decided(GraphRelationship relationship,
                                              Map<String, String> decisions) {
        String decision = decisions.get(relationship.id());
        if (decision == null) {
            return relationship;
        }
        if (!GraphRelationship.PROPOSED.equals(relationship.status())) {
            // Declared relationships need no review, and a confirmed one is
            // not undone here.
            return relationship;
        }
        String status = GraphRelationship.CONFIRMED.equals(decision)
                ? GraphRelationship.CONFIRMED
                : GraphRelationship.REJECTED;
        return new GraphRelationship(relationship.id(), relationship.from(), relationship.to(),
                relationship.origin(), relationship.scope(), status,
                relationship.confidence(), relationship.evidence());
    }

    /**
     * Builds the manual relationships whose two columns both exist, adding
     * their ids to {@code relationshipIds} so an overlay decision that names
     * the same pair is not an orphan either. Entries a column of which is
     * missing are skipped and their ids collected as orphans; entries whose
     * relationship id the document already has are skipped without becoming
     * orphans.
     */
    private static List<GraphRelationship> manualRelationships(Overlay overlay,
                                                                List<GraphEntity> entities,
                                                                Set<String> relationshipIds,
                                                                Set<String> manualOrphans) {
        Set<String> columnIds = columnIds(entities);
        List<GraphRelationship> added = new ArrayList<>();
        for (Overlay.ManualRelationship manual : overlay.manualRelationships()) {
            String id = GraphIds.relationshipId(manual.from(), manual.to());
            if (!columnIds.contains(manual.from()) || !columnIds.contains(manual.to())) {
                // A column is gone: the assertion matches nothing, so it is
                // reported as an orphan and kept in the overlay.
                manualOrphans.add(id);
                continue;
            }
            if (!relationshipIds.add(id)) {
                // The document already has this pair — the same relationship
                // as a discovered foreign key, or a proposal. Assert nothing
                // twice.
                continue;
            }
            added.add(new GraphRelationship(id, manual.from(), manual.to(),
                    GraphRelationship.MANUAL, scope(manual.from(), manual.to()),
                    GraphRelationship.CONFIRMED, null, List.of()));
        }
        return added;
    }

    /** Collects every overlay id that matched nothing, sorted and distinct. */
    private static List<String> orphans(Overlay overlay, Set<String> matchedLabelIds,
                                         Set<String> relationshipIds, Set<String> manualOrphans) {
        Set<String> orphans = new TreeSet<>(manualOrphans);
        for (String id : overlay.labels().keySet()) {
            if (!matchedLabelIds.contains(id)) {
                orphans.add(id);
            }
        }
        for (String id : overlay.relationshipDecisions().keySet()) {
            if (!relationshipIds.contains(id)) {
                orphans.add(id);
            }
        }
        return List.copyOf(orphans);
    }

    /** {@code cross-source} when the ends live in different sources. */
    private static String scope(String from, String to) {
        GraphIds.ColumnId fromParts = GraphIds.parseColumnId(from);
        GraphIds.ColumnId toParts = GraphIds.parseColumnId(to);
        return fromParts.source().equals(toParts.source())
                ? GraphRelationship.WITHIN_SOURCE
                : GraphRelationship.CROSS_SOURCE;
    }

    private static Set<String> columnIds(List<GraphEntity> entities) {
        Set<String> ids = new HashSet<>();
        for (GraphEntity entity : entities) {
            for (GraphColumn column : entity.columns()) {
                ids.add(column.id());
            }
        }
        return ids;
    }
}
