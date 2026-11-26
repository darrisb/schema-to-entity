package com.example.dynamicmeta.drift;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;
import com.example.dynamicmeta.graph.GraphIds;
import com.example.dynamicmeta.graph.Overlay;

/**
 * Works out what each {@link SchemaChange} of a drift check affects, by looking
 * for it in the {@link Overlay} and in the accepted baseline. Pure:
 * {@link #analyze(SchemaSnapshot, Overlay, List)} reads the baseline snapshot and
 * the overlay it is handed and returns a new list, changing neither. Nothing here
 * opens a connection, writes to a source database, or edits the overlay: an entry
 * listed as affected stays exactly where it is until a person changes it.
 * <p>
 * One {@link ChangeImpact} is produced per change, in the order the changes came
 * in, and each impact holds the four id lists a reviewer needs: the overlay's
 * labels, its relationship decisions, its manual relationships, and the
 * baseline's declared foreign keys written as relationship ids. A change that
 * refers to nothing still produces an impact with four empty lists rather than
 * being left out, so a report's {@code impact} always lines up with its
 * {@code changes} by position.
 * <p>
 * How an id is matched, always case-insensitively and ignoring surrounding
 * whitespace:
 * <ul>
 *   <li>An <em>entity</em> change (one with no column) owns the entity id
 *       {@code source:entity} and everything below it, so it matches ids equal to
 *       it or starting with {@code source:entity.} — its columns among them.
 *       Because a driver spells a table {@code CUSTOMERS} while the overlay
 *       spells it {@code shop:customers}, names are compared lower-cased.</li>
 *   <li>A <em>column</em> change owns exactly one column id,
 *       {@code source:entity.column}, and nothing else. Dropping a column does
 *       not report the label of its table: the table is still there.</li>
 *   <li>Relationship ids are checked on <em>both</em> sides of {@code ->}, so a
 *       cross-source decision is found whichever end the change belongs to, and a
 *       foreign key is found whether the changed table holds the column or is the
 *       one being referenced.</li>
 * </ul>
 * The {@code source} prefix comes from the baseline snapshot, so a change in one
 * source never reports the identically named table of another. A rename candidate
 * matches the object it names, not the one in {@link SchemaChange#relatedTo()}:
 * the removal and the addition it was derived from are reported separately and
 * each brings its own ids.
 * <p>
 * Ids are reported in the lower-cased shape {@link GraphIds} builds, which is how
 * the graph document and the overlay file spell them. A baseline foreign key whose
 * names cannot form ids (a source or table containing a separator character) is
 * skipped: the graph could not name that relationship either, so an id no one
 * could look up would mislead the reviewer.
 */
public final class ImpactAnalyzer {

    /** The separator {@link GraphIds} puts between a source and an entity name. */
    private static final char ENTITY_SEPARATOR = ':';

    /** The separator {@link GraphIds} puts between an entity and a column name. */
    private static final char COLUMN_SEPARATOR = '.';

    /** The separator {@link GraphIds} puts between the two ends of a relationship id. */
    private static final String RELATIONSHIP_SEPARATOR = "->";

    private ImpactAnalyzer() {
    }

    /**
     * Analyzes every change of one source against its baseline and the overlay.
     *
     * @param baseline the accepted snapshot the changes were diffed from; supplies
     *                 the source name and the declared foreign keys
     * @param overlay  the edits to look for references in, left untouched
     * @param changes  the changes to analyze, as {@link SnapshotDiff} produced them
     * @return one impact per change, in the same order; empty when there are no
     *         changes to analyze
     */
    public static List<ChangeImpact> analyze(SchemaSnapshot baseline, Overlay overlay,
                                             List<SchemaChange> changes) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(changes, "changes");

        String source = baseline.source();
        List<String> foreignKeys = foreignKeyIds(baseline);
        List<ChangeImpact> impacts = new ArrayList<>(changes.size());
        for (SchemaChange change : changes) {
            impacts.add(impactOf(source, overlay, change, foreignKeys));
        }
        return List.copyOf(impacts);
    }

    /**
     * Analyzes one change.
     *
     * @param baseline the accepted snapshot the change was found against
     * @param overlay  the edits to look for references in, left untouched
     * @param change   the change to analyze
     * @return what the change affects; empty lists when nothing refers to it
     */
    public static ChangeImpact impactOf(SchemaSnapshot baseline, Overlay overlay,
                                        SchemaChange change) {
        Objects.requireNonNull(baseline, "baseline");
        return impactOf(baseline.source(), overlay, change, foreignKeyIds(baseline));
    }

    /** The impact of one change, over the foreign key ids of its baseline. */
    private static ChangeImpact impactOf(String source, Overlay overlay, SchemaChange change,
                                         List<String> foreignKeys) {
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(change, "change");

        Targets targets = Targets.of(source, change);
        return new ChangeImpact(change,
                referring(targets, overlay.labels().keySet()),
                referring(targets, overlay.relationshipDecisions().keySet()),
                referring(targets, manualRelationshipIds(overlay)),
                referring(targets, foreignKeys));
    }

    /**
     * The overlay's manual relationships as relationship ids, which is how a
     * reviewer names them and how a decision about the same pair is keyed.
     */
    private static List<String> manualRelationshipIds(Overlay overlay) {
        List<String> ids = new ArrayList<>();
        for (Overlay.ManualRelationship manual : overlay.manualRelationships()) {
            ids.add(GraphIds.relationshipId(manual.from(), manual.to()));
        }
        return ids;
    }

    /**
     * The baseline's declared foreign keys as relationship ids, from the column
     * that holds the key to the column it references, both inside the source the
     * snapshot was taken from. Inferred relationships never reach a snapshot (see
     * {@link SnapshotBuilder}), so they are not listed here; what a person decided
     * about one reaches a reviewer through the overlay decisions.
     */
    private static List<String> foreignKeyIds(SchemaSnapshot baseline) {
        List<String> ids = new ArrayList<>();
        for (SnapshotEntity entity : baseline.entities()) {
            for (SnapshotForeignKey foreignKey : entity.foreignKeys()) {
                try {
                    String from = GraphIds.columnId(baseline.source(), entity.name(),
                            foreignKey.column());
                    String to = GraphIds.columnId(baseline.source(), foreignKey.referencedTable(),
                            foreignKey.referencedColumn());
                    ids.add(GraphIds.relationshipId(from, to));
                } catch (IllegalArgumentException unnameable) {
                    // A name that cannot be part of an id is not something the
                    // graph could show either, so there is nothing to refer to.
                }
            }
        }
        return ids;
    }

    /** Those of {@code candidates} that refer to what the change touched. */
    private static List<String> referring(Targets targets, Collection<String> candidates) {
        List<String> matched = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate != null && targets.relates(candidate)) {
                matched.add(normalized(candidate));
            }
        }
        return matched;
    }

    /**
     * The ids one change owns: its {@code source:entity}, and for a column change
     * the one column id below it. Names are normalized the way {@link GraphIds}
     * normalizes them — trimmed, lower-cased with the root locale — so a case the
     * driver happened to report never hides a reference.
     */
    private record Targets(String entityId, String columnId) {

        static Targets of(String source, SchemaChange change) {
            String entityId = normalized(source) + ENTITY_SEPARATOR + normalized(change.entity());
            String column = change.column();
            return new Targets(entityId,
                    column == null ? null : entityId + COLUMN_SEPARATOR + normalized(column));
        }

        /** True when an id, or either side of a relationship id, was touched. */
        boolean relates(String candidate) {
            String id = normalized(candidate);
            int arrow = id.indexOf(RELATIONSHIP_SEPARATOR);
            if (arrow < 0) {
                return owns(id);
            }
            return owns(id.substring(0, arrow))
                    || owns(id.substring(arrow + RELATIONSHIP_SEPARATOR.length()));
        }

        /** True when one id names the changed entity or the changed column. */
        private boolean owns(String id) {
            if (columnId != null) {
                return id.equals(columnId);
            }
            return id.equals(entityId) || id.startsWith(entityId + COLUMN_SEPARATOR);
        }
    }

    /** A name or id in the shape a graph id is stored in: trimmed, lower-cased. */
    private static String normalized(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
