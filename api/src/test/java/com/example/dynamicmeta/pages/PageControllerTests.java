package com.example.dynamicmeta.pages;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.data.ColumnCategory;
import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PageControllerTests {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PageController(new PageStore(tempDir), new PageDependencyExtractor()))
                .build();
    }

    @Test
    void putThenGetRoundTripsDocumentAndAddsDependencies() throws Exception {
        String body = """
                {"name":"Customers","description":"Customer page","document":[
                  {"id":"customers","dataSource":{"type":"rest",
                   "url":"/api/data/crm/customers","query":{"fields":"id,name"}}}
                ]}
                """;

        mockMvc.perform(put("/api/pages/customer-page")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("customer-page"))
                .andExpect(jsonPath("$.document[0].id").value("customers"));

        mockMvc.perform(get("/api/pages/customer-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Customers"))
                .andExpect(jsonPath("$.description").value("Customer page"))
                .andExpect(jsonPath("$.document[0].dataSource.query.fields")
                        .value("id,name"))
                .andExpect(jsonPath("$.dependencies.usages[0].entityId")
                        .value("crm:customers"));
    }

    @Test
    void listShowsPageAndItsEntities() throws Exception {
        putPage("sales-page", """
                [{"id":"orders","dataSource":{"type":"rest",
                  "url":"/api/data/sales/orders"}}]
                """);

        mockMvc.perform(get("/api/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sales-page"))
                .andExpect(jsonPath("$[0].name").value("Test page"))
                .andExpect(jsonPath("$[0].entities[0]").value("sales:orders"))
                .andExpect(jsonPath("$[0].status.pageId").value("sales-page"))
                .andExpect(jsonPath("$[0].status.state").value("unavailable"));
    }

    @Test
    void listKeepsCachedSchemaStatusesAlignedWithPages() throws Exception {
        SchemaCache cache = mock(SchemaCache.class);
        when(cache.get("crm")).thenReturn(customerSchema());
        when(cache.schemaVersion("crm")).thenReturn("current");
        PageDependencyExtractor extractor = new PageDependencyExtractor();
        mockMvc = MockMvcBuilders.standaloneSetup(new PageController(
                new PageStore(tempDir), extractor, new PageStatusService(cache, extractor)))
                .build();
        putPage("a-valid-page", """
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                  "query":{"fields":"name"}}}]
                """);
        putPage("b-broken-page", """
                [{"dataSource":{"type":"rest","url":"/api/data/crm/customers",
                  "query":{"fields":"missing"}}}]
                """);

        mockMvc.perform(get("/api/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a-valid-page"))
                .andExpect(jsonPath("$[0].status.pageId").value("a-valid-page"))
                .andExpect(jsonPath("$[0].status.state").value("ok"))
                .andExpect(jsonPath("$[1].id").value("b-broken-page"))
                .andExpect(jsonPath("$[1].status.pageId").value("b-broken-page"))
                .andExpect(jsonPath("$[1].status.state").value("broken"))
                .andExpect(jsonPath("$[1].status.missingColumns[0]")
                        .value("crm:customers.missing"));
    }

    @Test
    void statusReturnsPageStatusAndUnknownPageIs404() throws Exception {
        putPage("sales-page", """
                [{"dataSource":{"type":"rest","url":"/api/data/sales/orders"}}]
                """);

        mockMvc.perform(get("/api/pages/sales-page/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageId").value("sales-page"))
                .andExpect(jsonPath("$.state").value("unavailable"));
        mockMvc.perform(get("/api/pages/missing/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown page: missing"));
    }

    @Test
    void badIdAndDocumentAre400AndMissingPageIs404() throws Exception {
        mockMvc.perform(get("/api/pages/BAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(put("/api/pages/good-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"document\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("page document must be a JSON array"));
        mockMvc.perform(put("/api/pages/good-id")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid page document"));
        mockMvc.perform(get("/api/pages/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown page: missing"));
    }

    @Test
    void deleteThenGetIs404() throws Exception {
        putPage("temporary", "[]");

        mockMvc.perform(delete("/api/pages/temporary"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/pages/temporary"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/pages/temporary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void autoConfigurationRequiresPagesEnabled() {
        runner().run(context -> assertThat(context).doesNotHaveBean(PageController.class));

        runner("schema-to-entity.pages-enabled=true",
                "schema-to-entity.pages-dir=" + tempDir.resolve("configured"))
                .run(context -> {
                    assertThat(context).hasSingleBean(PageStore.class);
                    assertThat(context).hasSingleBean(PageDependencyExtractor.class);
                    assertThat(context).hasSingleBean(PageController.class);
                });
    }

    private void putPage(String id, String document) throws Exception {
        mockMvc.perform(put("/api/pages/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test page\",\"description\":\"Test\","
                                + "\"document\":" + document + "}"))
                .andExpect(status().isOk());
    }

    private WebApplicationContextRunner runner(String... properties) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ste_pages;DB_CLOSE_DELAY=-1");
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class, SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(properties);
    }

    private static SourceSchema customerSchema() {
        EntityModel customers = new EntityModel("CUSTOMERS", "table", List.of(
                new ColumnModel("ID", "bigint", ColumnCategory.NUMBER, false),
                new ColumnModel("NAME", "varchar", ColumnCategory.TEXT, true)),
                List.of("ID"));
        return new SourceSchema("h2", null, "PUBLIC", Map.of("customers", customers));
    }
}
