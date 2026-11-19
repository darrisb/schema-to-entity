package com.example.dynamicmeta.data;

import java.util.ArrayList;
import java.util.List;

import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DA-007: the read-only data endpoints are reachable from a browser on the
 * configured origins, while admin endpoints stay without CORS headers.
 */
class DataCorsTests {

    private static final String ALLOWED_ORIGIN = "https://app.example.com";
    private static final String OTHER_ORIGIN = "https://evil.example.com";

    @Test
    void preflightFromAllowedOriginSucceedsWithTheDocumentedHeaders() {
        runner("schema-to-entity.data-allowed-origins=" + ALLOWED_ORIGIN)
                .run(context -> mockMvc(context).perform(
                                options("/api/data/orders")
                                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                        .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,OPTIONS"))
                        .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)));
    }

    @Test
    void preflightFromAnotherOriginGetsNoCorsHeaders() {
        runner("schema-to-entity.data-allowed-origins=" + ALLOWED_ORIGIN)
                .run(context -> mockMvc(context).perform(
                                options("/api/data/orders")
                                        .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                        .andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void adminEndpointsNeverReturnCorsHeaders() {
        runner("schema-to-entity.data-allowed-origins=" + ALLOWED_ORIGIN)
                .run(context -> mockMvc(context).perform(
                                options("/api/schema/extract")
                                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                        .andExpect(status().is4xxClientError())
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void emptyAllowedOriginsSendsNoCorsHeaders() {
        runner()
                .run(context -> mockMvc(context).perform(
                                options("/api/data/orders")
                                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                        .andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void explicitWildcardAllowsAnyOriginWithoutCredentials() {
        runner("schema-to-entity.data-allowed-origins=*")
                .run(context -> mockMvc(context).perform(
                                options("/api/data/orders")
                                        .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)));
    }

    @Test
    void configurerIsRegisteredOnlyWhenDataApiIsEnabled() {
        runner("schema-to-entity.data-allowed-origins=" + ALLOWED_ORIGIN)
                .run(context -> assertThat(context)
                        .hasSingleBean(DataCorsConfiguration.class));

        runner("schema-to-entity.data-api-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DataCorsConfiguration.class));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static WebApplicationContextRunner runner(String... extraProperties) {
        JdbcDataSource host = new JdbcDataSource();
        host.setURL("jdbc:h2:mem:ste_data_cors_host;DB_CLOSE_DELAY=-1");
        host.setUser("sa");
        host.setPassword("");

        List<String> properties = new ArrayList<>(List.of(
                "schema-to-entity.data-api-enabled=true",
                "schema-to-entity.allow-embedded-databases=true",
                "schema-to-entity.sources.orders.url="
                        + "jdbc:h2:mem:ste_data_cors_orders;DB_CLOSE_DELAY=-1",
                "schema-to-entity.sources.orders.username=sa"));
        properties.addAll(List.of(extraProperties));

        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class, SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> host)
                .withPropertyValues(properties.toArray(new String[0]));
    }

    private static MockMvc mockMvc(org.springframework.web.context.WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }
}
