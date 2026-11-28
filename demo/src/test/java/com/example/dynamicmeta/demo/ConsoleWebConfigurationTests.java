package com.example.dynamicmeta.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * the delivered bytes, not just a recorded view name. {@link
 * ConsoleDockerfileTests} covers the image that produces the real bundle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleWebConfigurationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void consoleDirectoryServesTheIndexPage() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/console/", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType())
                .asString().contains("text/html");
        assertThat(response.getBody()).contains("Console test index");
    }

    @Test
    void consoleWithoutTrailingSlashRedirectsToTheCanonicalPath() throws Exception {
        // A plain client that follows redirects cannot tell the intended
        // 302 from a direct mapping, so disable automatic redirects and pin
        // the response of the /console entry itself.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/console"))
                .GET().build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        // The view controller's "redirect:" view name is delivered as the
        // absolute URL Spring builds for it; it must point at /console/.
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .endsWith(ConsoleWebConfiguration.CONSOLE_PATH);
    }

    @Test
    void consoleAssetsAreServedFromTheSameOrigin() {
        // The built index.html references /console/assets/** with absolute
        // paths (Vite base "/console/"), so those must resolve too.
        ResponseEntity<String> script =
                restTemplate.getForEntity("/console/assets/index-test.js", String.class);
        ResponseEntity<String> stylesheet =
                restTemplate.getForEntity("/console/assets/index-test.css", String.class);
        ResponseEntity<String> index =
                restTemplate.getForEntity("/console/index.html", String.class);

        assertThat(script.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(script.getBody()).contains("console test bundle");
        assertThat(stylesheet.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(stylesheet.getBody()).contains("system-ui");
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
