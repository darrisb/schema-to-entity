package com.example.dynamicmeta.demo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the README's MCP tool table synchronized with the adapter annotations. */
class McpDocsTests {

    @Test
    void documentedToolAndParameterNamesEqualTheAnnotatedMcpContract() throws IOException {
        Map<String, Set<String>> documented = documentedTools(
                Files.readString(Path.of("../api/README.md")));
        Map<String, Set<String>> annotated = new LinkedHashMap<>();
        for (Method method : McpTools.class.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) continue;
            Set<String> parameters = new LinkedHashSet<>();
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.getAnnotation(ToolParam.class))
                        .as("@ToolParam on %s.%s", method.getName(), parameter.getName())
                        .isNotNull();
                parameters.add(parameter.getName());
            }
            annotated.put(tool.name(), parameters);
        }

        assertThat(documented).isEqualTo(annotated);
    }

    private static Map<String, Set<String>> documentedTools(String readme) {
        String section = readme.substring(readme.indexOf("### MCP-capable assistants"),
                readme.indexOf("### Safety boundary"));
        Map<String, Set<String>> tools = new LinkedHashMap<>();
        for (String line : section.lines().toList()) {
            if (!line.startsWith("| `")) continue;
            String[] cells = line.split("\\|", -1);
            String tool = cells[1].trim().replace("`", "");
            String parameterCell = cells[2].trim();
            Set<String> parameters = new LinkedHashSet<>();
            if (!parameterCell.contains("none")) {
                Arrays.stream(parameterCell.split(","))
                        .map(String::trim)
                        .map(value -> value.replace("`", ""))
                        .forEach(parameters::add);
            }
            tools.put(tool, parameters);
        }
        return tools;
    }
}
