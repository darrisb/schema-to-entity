package com.example.dynamicmeta.pages;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** PG-008: the README's page storage documentation matches the implementation. */
class PagesDocsTests {

    private static final String PAGE_BASE = "/api/pages";
    private static final String PAGE_HEADING = "## Page storage";
    private static final Pattern STATE_ROW = Pattern.compile(
            "(?m)^\\| `([^`]+)` \\| .*\\|$");

    private final String readme;

    PagesDocsTests() throws IOException {
        readme = Files.readString(Path.of("README.md"));
    }

    @Test
    void documentsEveryPageEndpointTheControllerMaps() {
        String pages = pageSection();
        List<String> routes = mappedRoutes();

        assertThat(routes).containsExactlyInAnyOrder(
                "PUT " + PAGE_BASE + "/{id}",
                "GET " + PAGE_BASE,
                "GET " + PAGE_BASE + "/{id}",
                "GET " + PAGE_BASE + "/{id}/status",
                "DELETE " + PAGE_BASE + "/{id}");
        for (String route : routes) {
            assertThat(pages)
                    .as("documented endpoint heading for %s", route)
                    .contains("#### `" + route + "`");
        }
    }

    @Test
    void documentedPageFieldsMatchTheRecords() {
        String pages = pageSection();

        assertDocuments(pages, PageController.SavePageRequest.class);
        assertDocuments(pages, Page.class);
        assertDocuments(pages, PageController.PageSummary.class);
        assertDocuments(pages, PageController.PageDetail.class);
        assertDocuments(pages, PageDependencies.class);
        assertDocuments(pages, PageUsage.class);
        assertDocuments(pages, PageStatus.class);
    }

    @Test
    void documentsEveryPageStatusState() {
        Matcher matcher = STATE_ROW.matcher(pageSection());
        List<String> documented = new ArrayList<>();
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }
        documented.remove("state");

        assertThat(documented)
                .containsExactly("ok", "stale", "broken", "unavailable");
    }

    private static void assertDocuments(String pages, Class<?> recordType) {
        assertThat(recordType.isRecord()).isTrue();
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertThat(pages)
                    .as("%s field %s", recordType.getSimpleName(), component.getName())
                    .contains("`" + component.getName() + "`");
        }
    }

    private static List<String> mappedRoutes() {
        RequestMapping root = PageController.class.getAnnotation(RequestMapping.class);
        assertThat(root).isNotNull();
        assertThat(root.value()).containsExactly(PAGE_BASE);

        List<String> routes = new ArrayList<>();
        for (Method method : PageController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                routes.add("GET " + PAGE_BASE + pathOf(method.getAnnotation(GetMapping.class)));
            } else if (method.isAnnotationPresent(PutMapping.class)) {
                routes.add("PUT " + PAGE_BASE + pathOf(method.getAnnotation(PutMapping.class)));
            } else if (method.isAnnotationPresent(DeleteMapping.class)) {
                routes.add("DELETE " + PAGE_BASE + pathOf(method.getAnnotation(DeleteMapping.class)));
            }
        }
        return routes;
    }

    private static String pathOf(Annotation annotation) {
        String[] values = annotation instanceof GetMapping mapping ? mapping.value()
                : annotation instanceof PutMapping mapping ? mapping.value()
                : ((DeleteMapping) annotation).value();
        return values.length == 0 ? "" : values[0];
    }

    private String pageSection() {
        int from = readme.indexOf(PAGE_HEADING);
        assertThat(from).as("the README documents page storage").isGreaterThan(-1);
        int next = readme.indexOf("\n## ", from + PAGE_HEADING.length());
        return readme.substring(from, next < 0 ? readme.length() : next);
    }
}
