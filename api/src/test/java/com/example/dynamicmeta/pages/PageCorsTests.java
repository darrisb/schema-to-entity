package com.example.dynamicmeta.pages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

class PageCorsTests {

    private static final String PAGE_ORIGIN = "https://builder.example.com";
    private static final String DATA_ORIGIN = "https://app.example.com";

    @TempDir
    Path tempDir;

    @Test
    void preflightPutFromAllowedPageOriginSucceeds() {
        runner("schema-to-entity.page-allowed-origins=" + PAGE_ORIGIN)
                .run(context -> mockMvc(context).perform(options("/api/pages/customer-page")
                                .header(HttpHeaders.ORIGIN, PAGE_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                PAGE_ORIGIN))
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                                "GET,PUT,DELETE,OPTIONS"))
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                                "Content-Type"))
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)));
    }

    @Test
    void originAllowedOnlyForDataGetsNoPageCorsHeaders() {
        runner("schema-to-entity.data-allowed-origins=" + DATA_ORIGIN)
                .run(context -> mockMvc(context).perform(options("/api/pages/customer-page")
                                .header(HttpHeaders.ORIGIN, DATA_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                        .andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void pageOriginDoesNotChangeDataCorsBehaviour() {
        runner("schema-to-entity.data-api-enabled=true",
                "schema-to-entity.data-allowed-origins=" + DATA_ORIGIN,
                "schema-to-entity.page-allowed-origins=" + PAGE_ORIGIN)
                .run(context -> {
                    MockMvc mvc = mockMvc(context);
                    mvc.perform(options("/api/data/orders")
                                    .header(HttpHeaders.ORIGIN, DATA_ORIGIN)
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                            .andExpect(status().isOk())
                            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                    DATA_ORIGIN));
                    mvc.perform(options("/api/data/orders")
                                    .header(HttpHeaders.ORIGIN, PAGE_ORIGIN)
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                            .andExpect(status().isForbidden())
                            .andExpect(header().doesNotExist(
                                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
                });
    }

    @Test
    void configurationIsRegisteredOnlyWhenPagesAreEnabled() {
        runner().run(context -> assertThat(context)
                .hasSingleBean(PageCorsConfiguration.class));

        baseRunner("schema-to-entity.pages-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PageCorsConfiguration.class));
    }

    private WebApplicationContextRunner runner(String... extraProperties) {
        List<String> properties = new ArrayList<>(List.of(
                "schema-to-entity.pages-enabled=true",
                "schema-to-entity.pages-dir=" + tempDir.resolve("pages"),
                "schema-to-entity.allow-embedded-databases=true",
                "schema-to-entity.sources.orders.url="
                        + "jdbc:h2:mem:ste_page_cors_orders;DB_CLOSE_DELAY=-1",
                "schema-to-entity.sources.orders.username=sa"));
        properties.addAll(List.of(extraProperties));
        return baseRunner(properties.toArray(new String[0]));
    }

    private WebApplicationContextRunner baseRunner(String... properties) {
        JdbcDataSource host = new JdbcDataSource();
        host.setURL("jdbc:h2:mem:ste_page_cors_host;DB_CLOSE_DELAY=-1");
        host.setUser("sa");
        host.setPassword("");
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class, SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> host)
                .withPropertyValues(properties);
    }

    private static MockMvc mockMvc(
            org.springframework.web.context.WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }
}
