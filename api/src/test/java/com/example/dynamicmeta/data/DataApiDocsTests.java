package com.example.dynamicmeta.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DA-008: the README's data API documentation matches the implementation.
 * <p>
 * These tests read {@code api/README.md} and check that the documented
 * configuration keys, endpoints, query parameter names, response envelope and
 * status codes are the ones the starter actually implements.
 */
class DataApiDocsTests {

    private static final Pattern CURL =
            Pattern.compile("curl\\s+(?:\"([^\"]+)\"|(\\S+))");

    private static final List<String> RESERVED_PARAMETERS =
            List.of("fields", "sort", "limit", "offset", "total");

    private final String readme;

    DataApiDocsTests() throws IOException {
        readme = Files.readString(Path.of("README.md"));
    }

    @Test
    void documentsEveryConfigurationKey() {
        assertThat(readme).contains("data-api-enabled");
        assertThat(readme).contains("sources:");
        assertThat(readme).contains("view-keys");
        assertThat(readme).contains("viewKeys");
        assertThat(readme).contains("max-page-size");
        assertThat(readme).contains("default-page-size");
        assertThat(readme).contains("query-timeout");
        assertThat(readme).contains("schema-cache-ttl");
        assertThat(readme).contains("data-allowed-origins");
    }

    @Test
    void documentsEveryEndpointWithACurlExample() {
        assertThat(readme).contains("GET /api/data/{source}");
        assertThat(readme).contains("GET /api/data/{source}/{entity}");
        assertThat(readme).contains("GET /api/data/{source}/{entity}/{key}");

        // One curl example per data endpoint shape: the source listing, a page
        // query and a single-row lookup.
        List<String> urls = curlUrls();
        assertThat(urls).isNotEmpty();
        assertThat(urls).anyMatch(url -> url.matches(".*/api/data/[^/?]+/?(\\?.*)?"));
        assertThat(urls).anyMatch(url -> url.matches(".*/api/data/[^/?]+/[^/?]+(\\?.*)?"));
        assertThat(urls).anyMatch(url -> url.matches(".*/api/data/[^/?]+/[^/?]+/[^/?]+(\\?.*)?"));
    }

    @Test
    void documentedQueryParameterNamesMatchTheParser() {
        for (String name : RESERVED_PARAMETERS) {
            assertThat(readme).contains(name + "=");
        }
        assertThat(readme).contains("eq.<column>=");
        assertThat(readme).contains("contains.<column>=");

        // Every parameter name used by a README curl example must be one the
        // parser implements.
        for (String url : curlUrls()) {
            int question = url.indexOf('?');
            if (question < 0) {
                continue;
            }
            for (String pair : url.substring(question + 1).split("&")) {
                String name = pair.split("=", 2)[0];
                assertThat(isImplementedParameter(name))
                        .as("query parameter '%s' in %s", name, url)
                        .isTrue();
            }
        }

        // The documented names are accepted together by the parser. The
        // 'total' switch is stripped by DataController before parsing, so it is
        // not part of the parser's parameter set.
        EntityModel entity = new EntityModel("customers", "table", List.of(
                new ColumnModel("id", "bigint", ColumnCategory.NUMBER, false),
                new ColumnModel("name", "varchar(80)", ColumnCategory.TEXT, false),
                new ColumnModel("active", "boolean", ColumnCategory.BOOLEAN, true),
                new ColumnModel("created", "timestamp", ColumnCategory.TIMESTAMP, true),
                new ColumnModel("born", "date", ColumnCategory.DATE, true)),
                List.of("id"));

        QuerySpec spec = new QueryParameterParser(50, 200).parse(entity, Map.of(
                "fields", List.of("id,name"),
                "sort", List.of("-id"),
                "limit", List.of("10"),
                "offset", List.of("5"),
                "eq.active", List.of("true"),
                "contains.name", List.of("ar")));

        assertThat(spec.fields()).containsExactly("id", "name");
        assertThat(spec.sorts()).singleElement()
                .satisfies(sort -> {
                    assertThat(sort.column()).isEqualTo("id");
                    assertThat(sort.direction()).isEqualTo(QuerySpec.Direction.DESC);
                });
        assertThat(spec.limit()).isEqualTo(10);
        assertThat(spec.offset()).isEqualTo(5);
        assertThat(spec.equals()).containsEntry("active", Boolean.TRUE);
        assertThat(spec.contains()).containsEntry("name", "ar");
    }

    @Test
    void documentsTheResponseEnvelopeAndUiDataSource() {
        assertThat(readme).contains("\"items\"");
        assertThat(readme).contains("\"total\"");
        assertThat(readme).contains("\"limit\"");
        assertThat(readme).contains("\"offset\"");
        assertThat(readme).contains("responsePath: 'items'");
        assertThat(readme).contains("/api/data/demo/<entity>?fields=id,name");
    }

    @Test
    void documentsStatusCodes() {
        for (String status : List.of("200", "400", "404", "405", "502", "503", "504")) {
            assertThat(readme).contains(status);
        }
    }

    @Test
    void recommendsSelectOnlyGrantsAndStatesTheApiNeverWrites() {
        assertThat(readme).contains("SELECT-only");
        assertThat(readme).contains("never generates `INSERT`, `UPDATE`, `DELETE` or DDL");
    }

    private static boolean isImplementedParameter(String name) {
        return RESERVED_PARAMETERS.contains(name)
                || name.startsWith("eq.")
                || name.startsWith("contains.");
    }

    /**
     * The README's curl examples for the data API. Other features documented in
     * the same README (the graph endpoints, for example) have their own query
     * parameters, so only {@code /api/data/} examples are checked here.
     */
    private List<String> curlUrls() {
        List<String> urls = new ArrayList<>();
        Matcher matcher = CURL.matcher(readme);
        while (matcher.find()) {
            String url = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (url.contains("/api/data/")) {
                urls.add(url);
            }
        }
        return urls;
    }
}
