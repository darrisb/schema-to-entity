package com.example.dynamicmeta.pages;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceNotFoundException;
import com.example.dynamicmeta.data.SourceSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageStatusServiceTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PageDependencyExtractor extractor = new PageDependencyExtractor();

    @Test
    void existingColumnIsOkAndBecomesBrokenWhenColumnDisappears() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        AtomicReference<SourceSchema> current = new AtomicReference<>(schema("ID", "NAME"));
        when(cache.get("crm")).thenAnswer(invocation -> current.get());
        when(cache.schemaVersion("crm")).thenReturn("current");
        PageStatusService service = new PageStatusService(cache, extractor);
        Page page = page("""
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                  "query":{"fields":"name"}}}]
                """);

        assertThat(service.status(page).state()).isEqualTo("ok");

        current.set(schema("ID"));
        PageStatus broken = service.status(page);
        assertThat(broken.state()).isEqualTo("broken");
        assertThat(broken.missingColumns()).containsExactly("crm:customers.name");
    }

    @Test
    void differentRecordedVersionIsStale() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("crm")).thenReturn(schema("ID"));
        when(cache.schemaVersion("crm")).thenReturn("new-version");
        Page page = page("""
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                  "schemaVersion":"old-version"}}]
                """);

        PageStatus status = new PageStatusService(cache, extractor).status(page);

        assertThat(status.state()).isEqualTo("stale");
        assertThat(status.staleSources()).containsExactly("crm");
    }

    @Test
    void missingEntityIsBrokenAndBrokenTakesPrecedenceOverStale() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("crm")).thenReturn(new SourceSchema(
                "h2", null, "PUBLIC", Map.of()));
        when(cache.schemaVersion("crm")).thenReturn("new-version");
        Page page = page("""
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                  "schemaVersion":"old-version"}}]
                """);

        PageStatus status = new PageStatusService(cache, extractor).status(page);

        assertThat(status.state()).isEqualTo("broken");
        assertThat(status.missingEntities()).containsExactly("crm:customers");
        assertThat(status.staleSources()).containsExactly("crm");
    }

    @Test
    void pageListResolvesFailingSourceOnlyOnce() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("crm")).thenThrow(new IllegalStateException("unreachable"));
        Page first = page("""
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers"}}]
                """);
        Page second = new Page("second-page", "Second", null,
                first.document(), null, null);

        List<PageStatus> statuses = new PageStatusService(cache, extractor)
                .statuses(List.of(first, second));

        assertThat(statuses).extracting(PageStatus::state)
                .containsExactly("unavailable", "unavailable");
        verify(cache, times(1)).get("crm");
    }

    @Test
    void pageListResolvesSuccessfulSourceOnlyOnceAndKeepsStatusesAligned() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("crm")).thenReturn(schema("ID", "NAME"));
        when(cache.schemaVersion("crm")).thenReturn("current");
        Page first = page("""
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                  "query":{"fields":"name"}}}]
                """);
        Page second = new Page("second-page", "Second", null,
                mapper.readTree("""
                        [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                          "query":{"fields":"missing"}}}]
                        """), null, null);

        List<PageStatus> statuses = new PageStatusService(cache, extractor)
                .statuses(List.of(first, second));

        assertThat(statuses).extracting(PageStatus::pageId, PageStatus::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("customer-page", "ok"),
                        org.assertj.core.groups.Tuple.tuple("second-page", "broken"));
        assertThat(statuses.get(1).missingColumns())
                .containsExactly("crm:customers.missing");
        verify(cache, times(1)).get("crm");
    }

    @Test
    void unknownSourceIsUnavailableWithoutDetails() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("missing")).thenThrow(new SourceNotFoundException("missing"));
        Page page = page("""
                [{"dataSource":{"type":"rest","url":"/api/data/missing/customers"}}]
                """);

        PageStatus status = new PageStatusService(cache, extractor).status(page);

        assertThat(status.state()).isEqualTo("unavailable");
        assertThat(status.missingEntities()).isEmpty();
        assertThat(status.missingColumns()).isEmpty();
        assertThat(status.staleSources()).isEmpty();
    }

    private Page page(String document) throws Exception {
        return new Page("customer-page", "Customers", null,
                mapper.readTree(document), null, null);
    }

    private static SourceSchema schema(String... columns) {
        List<ColumnModel> models = java.util.Arrays.stream(columns)
                .map(name -> new ColumnModel(name, "varchar", ColumnCategory.TEXT, true))
                .toList();
        EntityModel customers = new EntityModel("CUSTOMERS", "table", models, List.of("ID"));
        return new SourceSchema("h2", null, "PUBLIC", Map.of("customers", customers));
    }
}
