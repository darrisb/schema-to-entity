package com.example.dynamicmeta.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Proposes cross-source relationships from column and entity names alone.
 * <p>
 * No database knows about another database's tables, so a link between two
 * sources can never be discovered from metadata the way a declared foreign
 * key can: it can only be proposed. {@link CrossSourceInference} is a pure
 * function over the entities of a {@link SchemaGraph}: it reads names and
 * type categories and returns {@code proposed} relationships. It never opens
 * a connection and nothing in it writes to any source database.
 * <p>
 * A column whose name ends in {@code _id} or {@code Id} — {@code customer_id}
 * or {@code customerId} — and that is not the entity's own key is a
 * candidate. Its stem ({@code customer}) is matched, case-insensitively,
 * against the names of entities in the <em>other</em> sources: the stem
 * itself, {@code stem+s}, {@code stem+es}, or the stem with a final
 * {@code y} turned into {@code ies} ({@code category} matches
 * {@code categories}). The target must have exactly one key column, and that
 * column's {@link com.example.dynamicmeta.data.ColumnCategory} must be the
 * same as the candidate column's — a NUMBER column can only propose a link to
 * a NUMBER key, TEXT to TEXT. Each match becomes a proposal at
 * {@link #BASE_CONFIDENCE}, plus {@link #KEY_NAME_BONUS} — capped at
 * {@link #MAX_CONFIDENCE} — when the target key is named {@code id} or carries
 * the same name as the candidate column. At most
 * {@link #MAX_PROPOSALS_PER_COLUMN} proposals are kept per column, highest
 * confidence first, ties broken by relationship id. A link inside one source
 * is never proposed here: within-source links come from declared foreign keys
 * and view inference instead.
 */
public final class CrossSourceInference {

    /** Confidence of a proposal whose target key adds no extra support. */
    public static final double BASE_CONFIDENCE = 0.6;

    /** Confidence added when the target key is named {@code id} or like the column. */
    public static final double KEY_NAME_BONUS = 0.2;

    /** Highest confidence a proposal can reach. */
    public static final double MAX_CONFIDENCE = 0.8;

    /** Proposals kept per candidate column, highest confidence first. */
    public static final int MAX_PROPOSALS_PER_COLUMN = 3;

    /** Evidence token for a target entity whose name matches the stem. */
    public static final String NAME_MATCH_EVIDENCE = "name-match";

    /** Evidence token for a target key of the same category as the column. */
    public static final String TYPE_MATCH_EVIDENCE = "type-match";

    /** Evidence token for a target key named {@code id} or like the column. */
    public static final String KEY_NAME_MATCH_EVIDENCE = "key-name-match";

    private static final String UNDERSCORE_ID_SUFFIX = "_id";
    private static final String CAMEL_ID_SUFFIX = "Id";

    private CrossSourceInference() {
    }

    /**
     * Proposes cross-source relationships for every candidate column of the
     * given entities. Pure: it only reads the entities and returns new
     * relationships, so the same entity list always produces the same
     * proposals.
     *
     * @param entities every entity of the document, across all sources
     * @return at most {@link #MAX_PROPOSALS_PER_COLUMN} proposals per
     *         candidate column, highest confidence first, ties by id
     */
    public static List<GraphRelationship> propose(List<GraphEntity> entities) {
        Objects.requireNonNull(entities, "entities");
        Map<String, List<GraphEntity>> bySource = new LinkedHashMap<>();
        for (GraphEntity entity : entities) {
            bySource.computeIfAbsent(sourceKey(entity.source()), key -> new ArrayList<>())
                    .add(entity);
        }

        List<GraphRelationship> proposals = new ArrayList<>();
        for (GraphEntity entity : entities) {
            for (GraphColumn column : entity.columns()) {
                String stem = candidateStem(entity, column);
                if (stem != null) {
                    proposals.addAll(proposeFromColumn(entity.source(), column, stem, bySource));
                }
            }
        }
        return proposals;
    }

    /**
     * Proposes relationships for one candidate column: every entity of every
     * <em>other</em> source whose name matches the stem and whose single key
     * column has the candidate's category. At most
     * {@link #MAX_PROPOSALS_PER_COLUMN} matches are returned, highest
     * confidence first, ties by id.
     */
    private static List<GraphRelationship> proposeFromColumn(String source, GraphColumn column,
                                                             String stem,
                                                             Map<String, List<GraphEntity>> bySource) {
        List<GraphRelationship> matches = new ArrayList<>();
        for (Map.Entry<String, List<GraphEntity>> other : bySource.entrySet()) {
            if (other.getKey().equals(sourceKey(source))) {
                // Never propose a link inside the same source: within-source
                // links come from declared and view-inferred foreign keys.
                continue;
            }
            for (GraphEntity target : other.getValue()) {
                if (nameMatches(target.name(), stem)) {
                    GraphRelationship proposal = proposeToEntity(column, target);
                    if (proposal != null) {
                        matches.add(proposal);
                    }
                }
            }
        }
        matches.sort(Comparator.comparingDouble(GraphRelationship::confidence).reversed()
                .thenComparing(GraphRelationship::id));
        int kept = Math.min(matches.size(), MAX_PROPOSALS_PER_COLUMN);
        return new ArrayList<>(matches.subList(0, kept));
    }

    /**
     * Builds the proposal from one candidate column to one matching target
     * entity, or null when the target has no single key column of the same
     * category as the candidate column.
     */
    private static GraphRelationship proposeToEntity(GraphColumn column, GraphEntity target) {
        if (target.keyColumns().size() != 1) {
            // Only a single-column key can be the far end of a proposal.
            return null;
        }
        GraphColumn key = keyColumnOf(target);
        if (key == null || key.category() != column.category()) {
            return null;
        }
        boolean keyNameMatch = key.name().equalsIgnoreCase("id")
                || key.name().equalsIgnoreCase(column.name());
        double confidence = Math.min(MAX_CONFIDENCE,
                BASE_CONFIDENCE + (keyNameMatch ? KEY_NAME_BONUS : 0.0));
        List<String> evidence = keyNameMatch
                ? List.of(NAME_MATCH_EVIDENCE, TYPE_MATCH_EVIDENCE, KEY_NAME_MATCH_EVIDENCE)
                : List.of(NAME_MATCH_EVIDENCE, TYPE_MATCH_EVIDENCE);
        String from = column.id();
        String to = key.id();
        return new GraphRelationship(GraphIds.relationshipId(from, to), from, to,
                GraphRelationship.INFERRED, GraphRelationship.CROSS_SOURCE,
                GraphRelationship.PROPOSED, confidence, evidence);
    }

    /**
     * Returns the lower-cased stem of a candidate column — the name without
     * its {@code _id} or {@code Id} suffix — or null when the column is not
     * a candidate: its name carries neither suffix, or it is the entity's own
     * key and names the row's own identity rather than a reference.
     */
    private static String candidateStem(GraphEntity entity, GraphColumn column) {
        String name = column.name();
        if (name == null || isOwnKey(entity, name)) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(UNDERSCORE_ID_SUFFIX)) {
            return withoutSuffix(lower, UNDERSCORE_ID_SUFFIX.length());
        }
        if (name.endsWith(CAMEL_ID_SUFFIX)) {
            return withoutSuffix(lower, CAMEL_ID_SUFFIX.length());
        }
        return null;
    }

    private static String withoutSuffix(String lowerName, int suffixLength) {
        String stem = lowerName.substring(0, lowerName.length() - suffixLength);
        return stem.isEmpty() ? null : stem;
    }

    private static boolean isOwnKey(GraphEntity entity, String columnName) {
        for (String key : entity.keyColumns()) {
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when the entity name is the stem or one of its simple
     * plural forms: the stem itself, {@code stem+s}, {@code stem+es}, or the
     * stem with a final {@code y} turned into {@code ies}. Matching ignores
     * case.
     */
    private static boolean nameMatches(String entityName, String stem) {
        String lower = entityName.toLowerCase(Locale.ROOT);
        return lower.equals(stem)
                || lower.equals(stem + "s")
                || lower.equals(stem + "es")
                || (stem.endsWith("y")
                    && lower.equals(stem.substring(0, stem.length() - 1) + "ies"));
    }

    /** Finds the target's single key column by name, or null when it is absent. */
    private static GraphColumn keyColumnOf(GraphEntity target) {
        String keyName = target.keyColumns().getFirst();
        for (GraphColumn column : target.columns()) {
            if (column.name() != null && column.name().equalsIgnoreCase(keyName)) {
                return column;
            }
        }
        return null;
    }

    /** Source names are already lower-case; this guards comparisons anyway. */
    private static String sourceKey(String source) {
        return source.toLowerCase(Locale.ROOT);
    }
}
