package com.example.dynamicmeta.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import com.example.dynamicmeta.llm.LlmTools;

/**
 * Minimal host application that consumes the schema-to-entity starter.
 *
 * <p>The starter discovers the {@code DataSource} this application configures and exposes
 * the {@code /api/schema} endpoints. Nothing from the starter is declared here on purpose, so
 * the demo also proves the auto-configuration works without manual bean wiring.
 */
@SpringBootApplication(scanBasePackages = "com.example.dynamicmeta.demo")
public class SchemaToEntityDemoApplication {

    @Bean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "llm-enabled",
            havingValue = "true")
    McpTools mcpTools(LlmTools llmTools) {
        return new McpTools(llmTools);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "llm-enabled",
            havingValue = "true")
    ToolCallbackProvider mcpToolCallbackProvider(McpTools mcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(mcpTools).build();
    }

    public static void main(String[] args) {
        SpringApplication.run(SchemaToEntityDemoApplication.class, args);
    }
}
