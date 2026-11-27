package com.example.dynamicmeta.demo;

import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.llm.LlmTools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Registers the demo's MCP tools after the starter has created its LLM services. */
@AutoConfiguration(after = SchemaToEntityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "schema-to-entity", name = "llm-enabled",
        havingValue = "true")
@ConditionalOnBean(LlmTools.class)
public class McpToolsAutoConfiguration {

    @Bean
    McpTools mcpTools(LlmTools llmTools) {
        return new McpTools(llmTools);
    }

    @Bean
    ToolCallbackProvider mcpToolCallbackProvider(McpTools mcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(mcpTools).build();
    }
}
