package com.example.dynamicmeta.pages;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageDependencyExtractorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PageDependencyExtractor extractor = new PageDependencyExtractor();

    @Test
    void extractsCustomerDropdownDependenciesAndSchemaVersion() throws Exception {
        JsonNode document = mapper.readTree("""
                [{"id":"customer-select","type":"dropdown","dataSource":{
                  "type":"rest","method":"GET",
                  "url":"http://localhost:8081/base/api/data/CRM/Customers",
                  "mapping":{"value":"id","label":"name"},
                  "query":{"fields":"id,name","limit":"200"},
                  "schemaVersion":"abc123def456"}}]
                """);

        PageDependencyExtractor.Extraction result = extractor.extract(document);

        assertThat(result.usages()).extracting(PageUsage::entityId)
                .containsOnly("crm:customers");
        assertThat(result.usages()).extracting(PageUsage::columnId)
                .contains("crm:customers.id", "crm:customers.name");
        assertThat(result.usages()).extracting(PageUsage::kind)
                .contains("data-source", "mapping", "query-field", "schema-version");
        assertThat(result.schemaVersions()).isEqualTo(Map.of("crm", "abc123def456"));
    }

    @Test
    void extractsFilterKeysAndDescendingSort() throws Exception {
        JsonNode document = mapper.readTree("""
                [{"id":"orders","dataSource":{"type":"rest","url":"/api/data/sales/orders",
                  "query":{"eq.customer_id":"42","contains.status":"open",
                           "sort":"-created_at"}}}]
                """);

        assertThat(extractor.extract(document).usages())
                .filteredOn(usage -> "query-field".equals(usage.kind()))
                .extracting(PageUsage::columnId)
                .containsExactlyInAnyOrder("sales:orders.customer_id", "sales:orders.status",
                        "sales:orders.created_at");
    }

    @Test
    void ignoresNonDataSourcesAndWalksNestedChildren() throws Exception {
        JsonNode document = mapper.readTree("""
                [{"id":"plain"},
                 {"id":"external","dataSource":{"type":"rest","url":"/api/report/customers"}},
                 {"id":"container","children":[
                   {"id":"nested","dataSource":{"type":"rest","url":"/api/data/crm/contacts"}}
                 ]}]
                """);

        PageDependencyExtractor.Extraction result = extractor.extract(document);

        assertThat(result.usages()).containsExactly(
                new PageUsage("nested", "data-source", "crm:contacts", null));
        assertThat(result.schemaVersions()).isEmpty();
    }

    @Test
    void returnsEmptyForNullAndNonArrayDocuments() throws Exception {
        assertThat(extractor.extract(null).usages()).isEmpty();
        assertThat(extractor.extract(mapper.readTree("{\"id\":\"component\"}")).usages())
                .isEmpty();
    }

    @Test
    void ignoresNonRestAndUnexpectedDataSourceShapes() throws Exception {
        JsonNode document = mapper.readTree("""
                [{"id":"static","dataSource":{"type":"static","url":"/api/data/crm/customers"}},
                 {"id":"sql","dataSource":{"type":"sql","url":"/api/data/crm/customers"}},
                 {"id":"missing-type","dataSource":{"url":"/api/data/crm/customers"}},
                 {"id":"text","dataSource":"rest"},
                 {"id":"array","dataSource":[]}]
                """);

        assertThat(extractor.extract(document).usages()).isEmpty();
    }

    @Test
    void toleratesMalformedNestedValuesAndInvalidUrls() throws Exception {
        JsonNode document = mapper.readTree("""
                [{"id":"bad-mapping","dataSource":{"type":"rest","url":"/api/data/crm/customers",
                    "mapping":"id","query":[]}},
                 {"id":"bad-query","dataSource":{"type":"rest","url":"/api/data/crm/orders",
                    "mapping":[],"query":{"sort":42}}},
                 {"id":"bad-url","dataSource":{"type":"rest","url":"http://[invalid"}},
                 {"id":"bad-entity","dataSource":{"type":"rest","url":"/api/data/crm/v2.customers"}}]
                """);

        assertThat(extractor.extract(document).usages()).containsExactly(
                new PageUsage("bad-mapping", "data-source", "crm:customers", null),
                new PageUsage("bad-query", "data-source", "crm:orders", null));
    }

    @Test
    void walksChildrenAtAnyDepth() throws Exception {
        JsonNode document = mapper.readTree("""
                [{"id":"outer","children":[{"id":"middle","children":[
                  {"id":"deep","dataSource":{"type":"rest","url":"/api/data/crm/contacts"}}
                ]}]}]
                """);

        assertThat(extractor.extract(document).usages()).containsExactly(
                new PageUsage("deep", "data-source", "crm:contacts", null));
    }
}
