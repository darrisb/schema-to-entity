package com.example.dynamicmeta.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.data.SourceRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DA-008: the demo application starts with the read-only data API enabled and
 * the {@code demo} source configured against the bundled Postgres.
 * <p>
 * The source is validated at startup but connects lazily, so this context test
 * does not require Postgres to be running.
 */
@SpringBootTest
class DemoApplicationTests {

    @Autowired
    private SchemaToEntityProperties properties;

    @Autowired
    private SourceRegistry sourceRegistry;

    @Test
    void dataApiIsEnabledWithTheDemoSource() {
        assertThat(properties.isDataApiEnabled()).isTrue();
        assertThat(properties.getSources()).containsKey("demo");
        assertThat(sourceRegistry.names()).containsExactly("demo");
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
}
