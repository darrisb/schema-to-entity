package com.example.dynamicmeta.graph;

import java.util.List;
import java.util.Objects;

/**
 * One relationship between two columns inside a {@link SchemaGraph}.
 * <p>
 * {@code from} and {@code to} are the {@link GraphIds#columnId} values at
 * the two ends, so a relationship can cross sources. {@code id} is the
 * {@link GraphIds#relationshipId} of that pair. {@code origin} says where
 * the relationship came from: {@code declared} for a foreign key read from
 * the database, {@code inferred} for one discovered by analysis and
 * {@code manual} for one a user asserted. Values other than those three are
 * normalized to {@code declared}. {@code scope} is {@code cross-source}
 * when the ends live in different sources and {@code within-source}
 * otherwise. {@code status} is the review state: {@code declared}
 * relationships need no review, anything else starts {@code proposed} and
 * moves to {@code confirmed} or {@code rejected}; unknown values are
 * normalized to {@code proposed}. {@code confidence} is null for declared
 * relationships and may be null for the others. {@code evidence} lists the
 * ids or notes the relationship was derived from.
 *
 * @param id         {@code fromColumnId->toColumnId}, lower-cased
 * @param from       column id the relationship starts at
 * @param to         column id the relationship points at
 * @param origin     {@code declared}, {@code inferred} or {@code manual}
 * @param scope      {@code within-source} or {@code cross-source}
 * @param status     {@code declared}, {@code proposed}, {@code confirmed} or {@code rejected}
 * @param confidence optional score, null for declared relationships
 * @param evidence   ids or notes supporting the relationship, possibly empty
 */
public record GraphRelationship(String id, String from, String to, String origin, String scope,
                                String status, Double confidence, List<String> evidence) {

    public static final String DECLARED = "declared";
    public static final String INFERRED = "inferred";
    public static final String MANUAL = "manual";

    public static final String WITHIN_SOURCE = "within-source";
    public static final String CROSS_SOURCE = "cross-source";

    public static final String PROPOSED = "proposed";
    public static final String CONFIRMED = "confirmed";
    public static final String REJECTED = "rejected";

    public GraphRelationship {
        id = Objects.requireNonNull(id, "id");
        from = Objects.requireNonNull(from, "from");
        to = Objects.requireNonNull(to, "to");
        origin = MANUAL.equals(origin) ? MANUAL
                : INFERRED.equals(origin) ? INFERRED
                : DECLARED;
        scope = CROSS_SOURCE.equals(scope) ? CROSS_SOURCE : WITHIN_SOURCE;
        status = CONFIRMED.equals(status) ? CONFIRMED
                : REJECTED.equals(status) ? REJECTED
                : DECLARED.equals(status) ? DECLARED
                : PROPOSED;
        if (DECLARED.equals(origin)) {
            confidence = null;
        }
        evidence = List.copyOf(evidence);
    }
}
