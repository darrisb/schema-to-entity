package com.example.dynamicmeta.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.data.SourceRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpSyncServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DA-008/GR-008: the demo application starts with the read-only data API
 * enabled and two sources configured against the bundled Postgres — the
 * {@code demo} tables in schema {@code public} and the {@code billing}
 * schema — so the combined schema document has cross-source proposals to
 * show. The sources are validated at startup but connect lazily, so this
 * context test does not require Postgres to be running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private SchemaToEntityProperties properties;

    @Autowired
    private SourceRegistry sourceRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private McpSyncServer mcpServer;

    @Test
    void mcpServerStartsWithAllSixTools() {
        assertThat(environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("spring.ai.mcp.server.name"))
                .isEqualTo("schema-to-entity");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol"))
                .isEqualTo("STREAMABLE");
        assertThat(environment.getProperty(
                "spring.ai.mcp.server.streamable-http.mcp-endpoint"))
                .isEqualTo("/mcp");
        assertThat(mcpServer).isNotNull();
        assertThat(toolCallbackProvider.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("list_sources", "describe_entity", "find_related",
                        "query_entity", "aggregate", "schema_summary");
    }

    @Test
    void llmDisabledRegistersNoMcpToolsAndTurnsOffTheServer() {
        try (ConfigurableApplicationContext context = runDemoContext(
                "--schema-to-entity.llm-enabled=false")) {
            assertThat(context.getBeansOfType(McpTools.class)).isEmpty();
            assertThat(context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(McpSyncServer.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty(
                    "spring.ai.mcp.server.enabled", Boolean.class)).isFalse();
        }
    }

    @Test
    void llmEnabledWithoutGraphStartsWithoutMcpTools() {
        try (ConfigurableApplicationContext context = runDemoContext(
                "--schema-to-entity.llm-enabled=true",
                "--schema-to-entity.graph-enabled=false")) {
            assertThat(context.getBeansOfType(McpTools.class)).isEmpty();
            assertThat(context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
        }
    }

    @Test
    void llmEnabledWithoutDataApiStartsWithoutMcpTools() {
        try (ConfigurableApplicationContext context = runDemoContext(
                "--schema-to-entity.llm-enabled=true",
                "--schema-to-entity.data-api-enabled=false")) {
            assertThat(context.getBeansOfType(McpTools.class)).isEmpty();
            assertThat(context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
        }
    }

    @Test
    void mcpClientListsAllSixToolsFromTheStreamableEndpoint() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        try (McpSyncClient client = McpClient.sync(transport).build()) {
            client.initialize();
            assertThat(client.listTools().tools())
                    .extracting(tool -> tool.name())
                    .containsExactlyInAnyOrder("list_sources", "describe_entity", "find_related",
                            "query_entity", "aggregate", "schema_summary");
        }
    }

    @Test
    void dataApiIsEnabledWithTheDemoSource() {
        assertThat(properties.isDataApiEnabled()).isTrue();
        assertThat(properties.getSources()).containsKey("demo");
        assertThat(sourceRegistry.names())
                .containsExactlyInAnyOrder("demo", "billing");
    }

    @Test
    void graphIsEnabledWithAnOverlayFileAndBothSources() {
        assertThat(properties.isGraphEnabled()).isTrue();
        assertThat(properties.getOverlayFile()).isNotBlank();
        assertThat(properties.getSources()).containsKey("billing");
        assertThat(properties.getSources().get("billing").getSchema())
                .isEqualTo("billing");
        assertThat(sourceRegistry.names()).contains("demo", "billing");
    }

    @Test
    void demoSourceIsConfiguredFromEnvironmentPlaceholders() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains("password: ${DEMO_DB_PASSWORD:");
        assertThat(yaml).contains("url: ${DEMO_DB_URL:");

        SchemaToEntityProperties.SourceProperties demo =
                properties.getSources().get("demo");
        assertThat(demo.getUrl()).isNotBlank();
        assertThat(demo.getUsername()).isNotBlank();
        assertThat(demo.getPassword()).isNotBlank();
        assertThat(demo.getSchema()).isNotBlank();
    }

    @Test
    void localUiDevServerIsAnAllowedOrigin() {
        assertThat(properties.getDataAllowedOrigins())
                .contains("http://localhost:4200");
    }

    @Test
    void driftCheckingRunsOnATimerWithBaselinesOnAVolume() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml"));

        // DR-009: drift needs the graph, and the demo checks in the background
        // so a reviewer sees a report without asking for one. Baselines are
        // read and written as local files, like the overlay; compose puts the
        // folder on the same /data volume. Nothing here touches a source
        // database, and the first scheduled check waits a full interval.
        assertThat(properties.isGraphEnabled()).isTrue();
        assertThat(properties.getDriftCheckInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getSnapshotDir()).isNotBlank();
        assertThat(yaml).contains("drift-check-interval: 15m");
        assertThat(yaml).contains("snapshot-dir: ${SNAPSHOT_DIR:");
    }

    @Test
    void pageStorageIsEnabledForTheVisualUiDevServerAndComposeVolume() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String compose = Files.readString(Path.of("../docker-compose.yml"));

        assertThat(yaml).contains("pages-enabled: true");
        assertThat(yaml).contains("pages-dir: ${PAGES_DIR:");
        assertThat(yaml).contains("page-allowed-origins:");
        assertThat(yaml).contains("- http://localhost:5173");
        assertThat(compose).contains("PAGES_DIR: /data/pages");
    }

    private ConfigurableApplicationContext runDemoContext(String... properties) {
        SpringApplication application = new SpringApplication(
                SchemaToEntityDemoApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        String[] arguments = new String[properties.length + 1];
        arguments[0] = "--server.port=0";
        System.arraycopy(properties, 0, arguments, 1, properties.length);
        return application.run(arguments);
    }
}
