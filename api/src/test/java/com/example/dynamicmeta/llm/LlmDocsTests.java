package com.example.dynamicmeta.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.dynamicmeta.data.AggregateParameterParser;
import com.example.dynamicmeta.data.AggregateSpec;
import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.DataController;
import com.example.dynamicmeta.data.DataQueryService;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.QueryParameterParser;
import com.example.dynamicmeta.data.QuerySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Executable contract between the README's LLM section and the HTTP implementation. */
class LlmDocsTests {

    private static final Pattern FILTER_PREFIX = Pattern.compile("`([a-z]+)\\.<column>`");
    private static final EntityModel ENTITY = new EntityModel("orders", "table", List.of(
            new ColumnModel("id", "bigint", ColumnCategory.NUMBER, false),
            new ColumnModel("status", "varchar", ColumnCategory.TEXT, true),
            new ColumnModel("active", "boolean", ColumnCategory.BOOLEAN, true)), List.of("id"));

    private final String llmSection;

    LlmDocsTests() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        llmSection = readme.substring(readme.indexOf("## LLM and assistant access"),
                readme.indexOf("## Connection layer"));
    }

    @Test
    void everyDocumentedFilterPrefixIsAcceptedByTheParser() {
        Matcher matcher = FILTER_PREFIX.matcher(llmSection);
        Set<String> documented = new java.util.LinkedHashSet<>();
        while (matcher.find()) documented.add(matcher.group(1));
        assertThat(documented).containsExactlyInAnyOrder(
                "eq", "neq", "contains", "isnull", "gt", "gte", "lt", "lte");

        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (String prefix : documented) {
            String column = switch (prefix) {
                case "contains" -> "status";
                case "isnull" -> "active";
                default -> "id";
            };
            String value = switch (prefix) {
                case "contains" -> "open";
                case "isnull" -> "true";
                default -> "10";
            };
            parameters.put(prefix + "." + column, List.of(value));
        }

        QuerySpec parsed = new QueryParameterParser(20, 200).parse(ENTITY, parameters);
        assertThat(parsed.equals()).containsKey("id");
        assertThat(parsed.notEquals()).containsKey("id");
        assertThat(parsed.contains()).containsEntry("status", "open");
        assertThat(parsed.nullChecks()).containsEntry("active", true);
        assertThat(parsed.ranges()).hasSize(4);
    }

    @Test
    void documentedAggregateParametersAreAcceptedByTheParser() {
        for (String name : List.of("group", "metric", "sort", "limit")) {
            assertThat(llmSection).contains("`" + name + "=");
        }
        AggregateSpec parsed = new AggregateParameterParser(new QueryParameterParser(20, 200))
                .parse(ENTITY, Map.of(
                        "group", List.of("status"),
                        "metric", List.of("sum:id"),
                        "sort", List.of("-sum_id"),
                        "limit", List.of("100")));

        assertThat(parsed.groupBy()).containsExactly("status");
        assertThat(parsed.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.function()).isEqualTo(AggregateSpec.Function.SUM);
            assertThat(metric.column()).isEqualTo("id");
        });
        assertThat(parsed.sort().column()).isEqualTo("sum_id");
        assertThat(parsed.limit()).isEqualTo(100);
    }

    @Test
    void documentedExpandParameterReachesTheQueryService() throws Exception {
        assertThat(llmSection).contains("`expand`");
        DataQueryService service = mock(DataQueryService.class);
        when(service.query(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new DataQueryService.DataPage(List.of(), 0L, 20, null));
        when(service.schemaVersion("sales")).thenReturn("version");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DataController(service)).build();

        mvc.perform(get("/api/data/sales/orders").param("expand", "customer_id"))
                .andExpect(status().isOk());

        verify(service).query("sales", "orders", Map.of(), true, List.of("customer_id"));
    }

    @Test
    void documentedSchemaSummaryParametersAreAcceptedByTheController() throws Exception {
        for (String name : List.of("sources", "format", "values")) {
            assertThat(llmSection).contains(name + "=");
        }
        SchemaSummary summary = mock(SchemaSummary.class);
        SchemaSummary.Document document = new SchemaSummary.Document(List.of());
        when(summary.summarize(Set.of("demo", "billing"), true)).thenReturn(document);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmController(summary)).build();

        mvc.perform(get("/api/llm/schema")
                        .param("sources", "demo,billing")
                        .param("format", "json")
                        .param("values", "true"))
                .andExpect(status().isOk());

        verify(summary).summarize(Set.of("demo", "billing"), true);
    }
}
