package com.example.dynamicmeta.drift;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one {@link SchemaChange} affects: every {@link com.example.dynamicmeta.graph.Overlay}
 * entry and every baseline foreign key that refers to the changed entity or
 * column, so a reviewer sees the consequences before accepting the drift.
 * <p>
 * Each list holds ids, in the same {@link com.example.dynamicmeta.graph.GraphIds}
 * shape the graph document and the overlay file use, so an id here is one the
 * reviewer can look up and edit:
 * <ul>
 *   <li>{@link #labels()} — entity or column ids the overlay has a label for.</li>
 *   <li>{@link #decisions()} — relationship ids the overlay has a
 *       {@code confirmed} or {@code rejected} decision for.</li>
 *   <li>{@link #manualRelationships()} — relationship ids a person asserted by
 *       hand over this entity or column.</li>
 *   <li>{@link #foreignKeys()} — the baseline's declared foreign keys, as
 *       relationship ids, that have this column or entity at either end.</li>
 *   <li>{@link #pages()} — saved pages and component ids that use the changed
 *       entity or column.</li>
 * </ul>
 * A list is empty when the change touches nothing of that kind; {@link #isEmpty()}
 * says when all five are. Nothing here is a judgment about whether the change is
 * safe to accept: it is only what refers to it.
 * <p>
 * Built by {@link ImpactAnalyzer}. This is a value object only: nothing in it
 * reads from or writes to any source database, and nothing in it changes the
 * overlay.
 *
 * @param change             the change whose consequences are listed
 * @param labels             overlay label ids referring to the change
 * @param decisions          overlay relationship-decision ids referring to it
 * @param manualRelationships overlay manual relationship ids referring to it
 * @param foreignKeys        baseline foreign keys, as relationship ids, that use it
 * @param pages              saved pages and components that use it
 */
public record ChangeImpact(SchemaChange change, List<String> labels, List<String> decisions,
                           List<String> manualRelationships, List<String> foreignKeys,
                           List<PageImpact> pages) {

    /** Backwards-compatible form for callers that do not analyze saved pages. */
    public ChangeImpact(SchemaChange change, List<String> labels, List<String> decisions,
                        List<String> manualRelationships, List<String> foreignKeys) {
        this(change, labels, decisions, manualRelationships, foreignKeys, List.of());
    }

    public ChangeImpact {
        change = Objects.requireNonNull(change, "change");
        labels = normalized(labels, "labels");
        decisions = normalized(decisions, "decisions");
        manualRelationships = normalized(manualRelationships, "manualRelationships");
        foreignKeys = normalized(foreignKeys, "foreignKeys");
        Objects.requireNonNull(pages, "pages must not be null");
        TreeSet<PageImpact> sortedPages = new TreeSet<>(
                java.util.Comparator.comparing(PageImpact::pageId));
        for (PageImpact page : pages) {
            sortedPages.add(Objects.requireNonNull(page, "pages must not contain null"));
        }
        pages = List.copyOf(sortedPages);
    }

    /** An impact that names nothing: no edit, foreign key or saved page is affected. */
    public static ChangeImpact none(SchemaChange change) {
        return new ChangeImpact(change, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * True when nothing refers to the change: no label, no decision, no manual
     * relationship, baseline foreign key or saved page uses the changed entity or column.
     */
    public boolean isEmpty() {
        return labels.isEmpty() && decisions.isEmpty() && manualRelationships.isEmpty()
                && foreignKeys.isEmpty() && pages.isEmpty();
    }

    /**
     * Copies ids into an immutable list that is distinct and sorted, so the same
     * analysis always reads the same way, and a repeated id is named once.
     */
    private static List<String> normalized(List<String> ids, String what) {
        Objects.requireNonNull(ids, what + " must not be null");
        Set<String> unique = new TreeSet<>();
        for (String id : ids) {
            unique.add(Objects.requireNonNull(id, what + " must not contain a null id"));
        }
        return List.copyOf(unique);
    }
}
