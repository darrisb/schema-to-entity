package com.example.dynamicmeta.graph;

import java.util.List;

import com.example.dynamicmeta.data.ColumnCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GR-004: no database knows about another database's tables, so a
 * cross-source link can only be proposed. {@link CrossSourceInference} is a
 * pure function over the entities of a {@link SchemaGraph}: these tests build
 * entities in memory and never touch a database.
 */
class CrossSourceInferenceTests {

    @Test
    void proposesACrossSourceRelationshipFromAnIdColumnToAMatchingEntity() {
        GraphEntity orders = entity("shop", "orders",
                List.of(column("shop", "orders", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "orders", "customer_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        GraphEntity customers = entity("crm", "customers",
                List.of(column("crm", "customers", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));

        List<GraphRelationship> proposals =
                CrossSourceInference.propose(List.of(orders, customers));

        assertThat(proposals).hasSize(1);
        GraphRelationship proposal = proposals.getFirst();
        assertThat(proposal.id()).isEqualTo("shop:orders.customer_id->crm:customers.id");
        assertThat(proposal.from()).isEqualTo("shop:orders.customer_id");
        assertThat(proposal.to()).isEqualTo("crm:customers.id");
        assertThat(proposal.origin()).isEqualTo(GraphRelationship.INFERRED);
        assertThat(proposal.scope()).isEqualTo(GraphRelationship.CROSS_SOURCE);
        assertThat(proposal.status()).isEqualTo(GraphRelationship.PROPOSED);
        // The target key is named id, so the key-name bonus applies.
        assertThat(proposal.confidence()).isEqualTo(0.8);
        assertThat(proposal.evidence())
                .containsExactly("name-match", "type-match", "key-name-match");
    }

    @Test
    void camelCaseColumnsAreCandidatesAndAnUnhelpfulTargetKeyStaysAtBaseConfidence() {
        GraphEntity orders = entity("shop", "orders",
                List.of(column("shop", "orders", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "orders", "customerId", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        // The key is named neither id nor like the candidate column, so no
        // key-name bonus and no key-name-match evidence.
        GraphEntity customers = entity("crm", "customers",
                List.of(column("crm", "customers", "ref", "bigint", ColumnCategory.NUMBER)),
                List.of("ref"));

        List<GraphRelationship> proposals =
                CrossSourceInference.propose(List.of(orders, customers));

        assertThat(proposals).hasSize(1);
        GraphRelationship proposal = proposals.getFirst();
        // Column ids are lower-cased whatever the discovered casing was.
        assertThat(proposal.id()).isEqualTo("shop:orders.customerid->crm:customers.ref");
        assertThat(proposal.confidence()).isEqualTo(0.6);
        assertThat(proposal.evidence()).containsExactly("name-match", "type-match");
    }

    @Test
    void categoryMismatchProducesNoProposal() {
        GraphEntity orders = entity("shop", "orders",
                List.of(column("shop", "orders", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "orders", "customer_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        // A text key cannot hold the number customer_id carries.
        GraphEntity customers = entity("crm", "customers",
                List.of(column("crm", "customers", "id", "varchar(40)", ColumnCategory.TEXT)),
                List.of("id"));

        assertThat(CrossSourceInference.propose(List.of(orders, customers))).isEmpty();
    }

    @Test
    void compositeTargetKeyProducesNoProposal() {
        GraphEntity orders = entity("shop", "orders",
                List.of(column("shop", "orders", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "orders", "customer_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        GraphEntity customers = entity("crm", "customers",
                List.of(column("crm", "customers", "id", "bigint", ColumnCategory.NUMBER),
                        column("crm", "customers", "tenant_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id", "tenant_id"));

        assertThat(CrossSourceInference.propose(List.of(orders, customers))).isEmpty();
    }

    @Test
    void sameSourceTargetProducesNoProposal() {
        // Both entities live in shop: within-source links come from declared
        // and view-inferred foreign keys, never from name inference.
        GraphEntity orders = entity("shop", "orders",
                List.of(column("shop", "orders", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "orders", "customer_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        GraphEntity customers = entity("shop", "customers",
                List.of(column("shop", "customers", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));

        assertThat(CrossSourceInference.propose(List.of(orders, customers))).isEmpty();
    }

    @Test
    void aColumnThatIsTheEntitySOwnKeyIsNeverACandidate() {
        // accounts is itself keyed by customer_id: the column names the row's
        // own identity, so it must not propose a link to crm:customers.
        GraphEntity accounts = entity("shop", "accounts",
                List.of(column("shop", "accounts", "customer_id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "accounts", "balance", "decimal(10,2)", ColumnCategory.NUMBER)),
                List.of("customer_id"));
        GraphEntity customers = entity("crm", "customers",
                List.of(column("crm", "customers", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));

        assertThat(CrossSourceInference.propose(List.of(accounts, customers))).isEmpty();
    }

    @Test
    void pluralFormsMatchIncludingYToIes() {
        GraphEntity products = entity("shop", "products",
                List.of(column("shop", "products", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "products", "category_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        GraphEntity categories = entity("warehouse", "categories",
                List.of(column("warehouse", "categories", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));

        List<GraphRelationship> proposals =
                CrossSourceInference.propose(List.of(products, categories));

        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().id())
                .isEqualTo("shop:products.category_id->warehouse:categories.id");
    }

    @Test
    void moreThanThreeCandidatesKeepsTheHighestConfidenceThreeWithTiesBrokenById() {
        GraphEntity orders = entity("shop", "orders",
                List.of(column("shop", "orders", "id", "bigint", ColumnCategory.NUMBER),
                        column("shop", "orders", "customer_id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        // Four sources all have an entity the stem "customer" matches. The
        // alpha key is named ref, which earns no key-name bonus, so its 0.6
        // proposal loses to the three 0.8 proposals even though its id
        // sorts first.
        GraphEntity alpha = entity("alpha", "customers",
                List.of(column("alpha", "customers", "ref", "bigint", ColumnCategory.NUMBER)),
                List.of("ref"));
        GraphEntity billing = entity("billing", "customers",
                List.of(column("billing", "customers", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        GraphEntity crm = entity("crm", "customers",
                List.of(column("crm", "customers", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));
        GraphEntity partners = entity("partners", "customers",
                List.of(column("partners", "customers", "id", "bigint", ColumnCategory.NUMBER)),
                List.of("id"));

        List<GraphRelationship> proposals = CrossSourceInference.propose(
                List.of(orders, alpha, billing, crm, partners));

        assertThat(proposals).extracting(GraphRelationship::id).containsExactly(
                "shop:orders.customer_id->billing:customers.id",
                "shop:orders.customer_id->crm:customers.id",
                "shop:orders.customer_id->partners:customers.id");
        assertThat(proposals).allSatisfy(
                proposal -> assertThat(proposal.confidence()).isEqualTo(0.8));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static GraphColumn column(String source, String entity, String name,
                                      String type, ColumnCategory category) {
        return new GraphColumn(GraphIds.columnId(source, entity, name), name, type, category,
                false, null, null);
    }

    private static GraphEntity entity(String source, String name, List<GraphColumn> columns,
                                      List<String> keyColumns) {
        return new GraphEntity(GraphIds.entityId(source, name), source, name, "table",
                null, null, keyColumns, columns, List.of());
    }
}
