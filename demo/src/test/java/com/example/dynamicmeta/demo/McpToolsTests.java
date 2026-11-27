package com.example.dynamicmeta.demo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.dynamicmeta.llm.LlmTools;
import com.example.dynamicmeta.llm.SchemaSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolsTests {

    private final LlmTools delegate = mock(LlmTools.class);
    private final McpTools tools = new McpTools(delegate);

    @Test
    void delegatesEveryOperationAndReturnsTheExactResult() {
        List<Map<String, Object>> sourcesResult = List.of(Map.of("name", "demo"));
        Map<String, Object> descriptionResult = Map.of("id", "demo:orders");
        Object relatedResult = List.of(Map.of("entityId", "demo:customers"));
        Map<String, Object> queryResult = Map.of("items", List.of());
        Map<String, Object> aggregateResult = Map.of("rows", List.of());
        SchemaSummary.Document summaryResult = new SchemaSummary.Document(List.of());

        Map<String, Object> filters = Map.of("eq.status", "PAID");
        List<String> fields = List.of("id", "status");
        List<String> sort = List.of("-id");
        List<String> expand = List.of("customer");
        List<String> group = List.of("status");
        List<String> metrics = List.of("count");
        Set<String> summarySources = Set.of("demo");

        when(delegate.listSources()).thenReturn(sourcesResult);
        when(delegate.describeEntity("demo:orders")).thenReturn(descriptionResult);
        when(delegate.findRelated("demo:orders")).thenReturn(relatedResult);
        when(delegate.queryEntity("demo:orders", filters, fields, sort, 10, expand))
                .thenReturn(queryResult);
        when(delegate.aggregate("demo:orders", group, metrics, filters, sort, 10))
                .thenReturn(aggregateResult);
        when(delegate.schemaSummary(summarySources, true)).thenReturn(summaryResult);

        assertThat(tools.listSources()).isSameAs(sourcesResult);
        assertThat(tools.describeEntity("demo:orders")).isSameAs(descriptionResult);
        assertThat(tools.findRelated("demo:orders")).isSameAs(relatedResult);
        assertThat(tools.queryEntity("demo:orders", filters, fields, sort, 10, expand))
                .isSameAs(queryResult);
        assertThat(tools.aggregate("demo:orders", group, metrics, filters, sort, 10))
                .isSameAs(aggregateResult);
        assertThat(tools.schemaSummary(summarySources, true)).isSameAs(summaryResult);

        verify(delegate).listSources();
        verify(delegate).describeEntity("demo:orders");
        verify(delegate).findRelated("demo:orders");
        verify(delegate).queryEntity("demo:orders", filters, fields, sort, 10, expand);
        verify(delegate).aggregate("demo:orders", group, metrics, filters, sort, 10);
        verify(delegate).schemaSummary(summarySources, true);
    }
}
