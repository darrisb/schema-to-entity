package com.example.dynamicmeta.demo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.dynamicmeta.llm.LlmTools;
import com.example.dynamicmeta.llm.SchemaSummary;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** MCP-facing names and descriptions for the starter's read-only LLM operations. */
public final class McpTools {

    private final LlmTools delegate;

    public McpTools(LlmTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = "list_sources", description = "List the configured read-only data sources.")
    public Object listSources() {
        return delegate.listSources();
    }

    @Tool(name = "describe_entity", description = "Describe an entity's columns and relationships. Entity IDs use source:entity.")
    public Map<String, Object> describeEntity(
            @ToolParam(description = "Entity ID in source:entity form") String entityId) {
        return delegate.describeEntity(entityId);
    }

    @Tool(name = "find_related", description = "Find declared or confirmed relationships for an entity.")
    public Object findRelated(
            @ToolParam(description = "Entity ID in source:entity form") String entityId) {
        return delegate.findRelated(entityId);
    }

    @Tool(name = "query_entity", description = "Run a read-only SELECT built from the cached schema. Filter map keys are eq.column, contains.column, gt.column, gte.column, lt.column, lte.column, neq.column, or isnull.column; values are always bound parameters.")
    public Map<String, Object> queryEntity(
            @ToolParam(description = "Entity ID in source:entity form") String entityId,
            @ToolParam(description = "Filters keyed by eq./contains./gt./gte./lt./lte./neq./isnull. plus a cached-schema column name") Map<String, ?> filters,
            @ToolParam(description = "Columns to return; empty selects all visible columns", required = false) List<String> fields,
            @ToolParam(description = "Cached-schema columns to sort; prefix descending columns with '-'", required = false) List<String> sort,
            @ToolParam(description = "Maximum rows, from 1 through 100", required = false) Integer limit,
            @ToolParam(description = "Relationships to expand", required = false) List<String> expand) {
        return delegate.queryEntity(entityId, filters, fields, sort, limit, expand);
    }

    @Tool(name = "aggregate", description = "Run a read-only aggregate SELECT built from the cached schema. Filter map keys are eq.column, contains.column, gt.column, gte.column, lt.column, lte.column, neq.column, or isnull.column; values are always bound parameters.")
    public Map<String, Object> aggregate(
            @ToolParam(description = "Entity ID in source:entity form") String entityId,
            @ToolParam(description = "Cached-schema columns to group by", required = false) List<String> group,
            @ToolParam(description = "Metrics such as count or sum:column") List<String> metrics,
            @ToolParam(description = "Filters keyed by eq./contains./gt./gte./lt./lte./neq./isnull. plus a cached-schema column name") Map<String, ?> filters,
            @ToolParam(description = "Aggregate output fields to sort; prefix descending fields with '-'", required = false) List<String> sort,
            @ToolParam(description = "Maximum rows, from 1 through 100", required = false) Integer limit) {
        return delegate.aggregate(entityId, group, metrics, filters, sort, limit);
    }

    @Tool(name = "schema_summary", description = "Return a compact read-only schema summary, optionally limited to named sources and optionally including bounded distinct text values.")
    public SchemaSummary.Document schemaSummary(
            @ToolParam(description = "Source names to include; empty includes every source", required = false) Set<String> sources,
            @ToolParam(description = "Whether to include bounded distinct text values") boolean includeValues) {
        return delegate.schemaSummary(sources, includeValues);
    }
}
