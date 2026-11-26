package com.example.dynamicmeta.drift;

import java.time.Instant;
import java.util.List;

import com.example.dynamicmeta.drift.SchemaChange.Severity;
import com.example.dynamicmeta.drift.SchemaChange.Type;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.graph.Overlay;
import com.example.dynamicmeta.pages.Page;
import com.example.dynamicmeta.pages.PageDependencyExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageImpactTests {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PageDependencyExtractor extractor = new PageDependencyExtractor();

    @Test
    void droppingAColumnUsedByASavedPageListsThePageAndTableComponent() throws Exception {
        Page page = page("orders-page", """
                [{
                  "id": "orders-table",
                  "type": "table",
                  "dataSource": {
                    "type": "rest",
                    "url": "/api/data/shop/orders",
                    "query": {"fields": "id,total"}
                  },
                  "properties": {"columns": "id|ID\\ntotal|Total"}
                }]
                """);

        ChangeImpact impact = analyze(columnChange("orders", "total"), List.of(page));

        assertThat(impact.pages()).containsExactly(
                new PageImpact("orders-page", List.of("orders-table")));
    }

    @Test
    void pagesAreEmptyWhenPageAnalysisIsDisabled() throws Exception {
        SchemaChange change = columnChange("orders", "total");
        Page page = page("orders-page", """
                [{"id":"orders-table","type":"table","dataSource":{
                  "type":"rest","url":"/api/data/shop/orders",
                  "query":{"fields":"total"}}}]
                """);

        ChangeImpact unchanged = ImpactAnalyzer.analyze(
                baseline(), Overlay.empty(), List.of(change)).getFirst();

        assertThat(unchanged.pages()).isEmpty();
        assertThat(unchanged).isEqualTo(ChangeImpact.none(change));
        // Supplying the page is what opts the analyzer into page impact.
        assertThat(analyze(change, List.of(page)).pages()).isNotEmpty();
    }

    @Test
    void anEntityChangeListsPagesUsingAnyOfItsColumnsInStableOrder() throws Exception {
        Page later = page("z-page", """
                [{"id":"z-table","type":"table","dataSource":{
                  "type":"rest","url":"/api/data/shop/orders",
                  "mapping":{"value":"total"}}}]
                """);
        Page earlier = page("a-page", """
                [{"id":"table-b","type":"table","dataSource":{
                  "type":"rest","url":"/api/data/shop/orders",
                  "query":{"fields":"id,total"}}},
                 {"id":"table-a","type":"table","dataSource":{
                  "type":"rest","url":"/api/data/shop/orders",
                  "query":{"fields":"total"}}}]
                """);

        ChangeImpact impact = analyze(entityChange("orders"), List.of(later, earlier));

        assertThat(impact.pages()).extracting(PageImpact::pageId)
                .containsExactly("a-page", "z-page");
        assertThat(impact.pages().getFirst().componentIds())
                .containsExactly("table-a", "table-b");
    }

    private ChangeImpact analyze(SchemaChange change, List<Page> pages) {
        return ImpactAnalyzer.analyze(baseline(), Overlay.empty(), List.of(change),
                pages, extractor).getFirst();
    }

    private static SchemaSnapshot baseline() {
        SnapshotEntity orders = new SnapshotEntity("orders", "table", List.of(), List.of(),
                List.of());
        return new SchemaSnapshot("shop", "0".repeat(64), NOW, "h2", List.of(orders));
    }

    private static SchemaChange columnChange(String entity, String column) {
        return new SchemaChange(Type.COLUMN_REMOVED, Severity.BREAKING, entity, column,
                "decimal nullable", null, null);
    }

    private static SchemaChange entityChange(String entity) {
        return new SchemaChange(Type.ENTITY_REMOVED, Severity.BREAKING, entity, null,
                "table", null, null);
    }

    private static Page page(String id, String document) throws Exception {
        JsonNode json = MAPPER.readTree(document);
        return new Page(id, id, null, json, NOW, NOW);
    }
}
