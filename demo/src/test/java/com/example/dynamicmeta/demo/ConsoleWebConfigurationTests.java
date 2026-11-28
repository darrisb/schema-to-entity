package com.example.dynamicmeta.demo;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CN-009: the console is served by the API itself, on the API's origin.
 *
 * <p>A fixture bundle at {@code static/console/} on the test classpath stands
 * in for the Vite build the Docker image copies into the jar. Boot's static
 * handling serves those files; {@link ConsoleWebConfiguration} adds the
 * {@code /console} and {@code /console/} entries. The random-port environment
 * runs a real container so the forward is dispatched and the assertions see
 * the delivered bytes, not just a recorded view name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleWebConfigurationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void consoleDirectoryServesTheIndexPage() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/console/", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("Console test index");
    }

    @Test
    void consoleWithoutTrailingSlashReachesTheIndexPage() {
        // TestRestTemplate follows the redirect, so a 200 here proves the
        // whole chain: /console -> /console/ -> the index page.
        ResponseEntity<String> response =
                restTemplate.getForEntity("/console", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("Console test index");
    }

    @Test
    void consoleAssetsAreServedFromTheSameOrigin() {
        // The built index.html references /console/assets/** with absolute
        // paths (Vite base "/console/"), so those must resolve too.
        ResponseEntity<String> script =
                restTemplate.getForEntity("/console/assets/index-test.js", String.class);
        ResponseEntity<String> index =
                restTemplate.getForEntity("/console/index.html", String.class);

        assertThat(script.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(script.getBody()).contains("console test bundle");
        assertThat(index.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(index.getBody()).contains("Console test index");
    }

    @Test
    void unknownConsolePathIsNotRewrittenToTheIndexPage() {
        // Only the two console entries are added; a missing asset stays a 404
        // and the untouched root does not serve the console index.
        assertThat(restTemplate.getForEntity("/console/assets/missing.js",
                String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(restTemplate.getForEntity("/unknown", String.class)
                .getStatusCode().value()).isEqualTo(404);
    }
}
