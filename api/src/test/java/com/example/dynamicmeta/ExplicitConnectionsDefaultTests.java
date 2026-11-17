package com.example.dynamicmeta;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FX-008: explicit database connections must stay off unless the host application opts in.
 */
class ExplicitConnectionsDefaultTests {

    private static final String EXTRACT_REQUEST =
            "{\"connectionString\":\"jdbc:h2:mem:explicit_default;DB_CLOSE_DELAY=-1\"}";

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:explicit_default_host;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, ExplicitConnectionsDefaultTests::dataSource);
    }

    @Test
    void explicitConnectionsAreDisabledByDefault() throws Exception {
        runner().run(context -> {
            assertThat(context.getBean(SchemaToEntityProperties.class).isExplicitConnectionsEnabled())
                    .isFalse();

            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(context.getBean(SchemaDiscoveryController.class))
                    .build();
            mockMvc.perform(post("/api/schema/extract")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(EXTRACT_REQUEST))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Explicit database connections are disabled"));
        });
    }

    @Test
    void explicitConnectionsWorkWhenEnabled() throws Exception {
        runner()
                .withPropertyValues(
                        "schema-to-entity.explicit-connections-enabled=true",
                        "schema-to-entity.allow-embedded-databases=true")
                .run(context -> {
                    MockMvc mockMvc = MockMvcBuilders
                            .standaloneSetup(context.getBean(SchemaDiscoveryController.class))
                            .build();
                    mockMvc.perform(post("/api/schema/extract")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(EXTRACT_REQUEST))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.dialect").value("h2"));
                });
    }
}
