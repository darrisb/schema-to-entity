package com.example.dynamicmeta.drift;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-009: the README's drift documentation matches the implementation.
 *
 * <p>These tests read {@code api/README.md} and check that the documented
 * drift endpoint paths are the ones {@link DriftController} actually maps, that
 * every field name in the documented JSON examples is an actual record
 * component, that the documented statuses, change types, severities, rename
 * thresholds and configuration keys match the code, and that the two statements
 * that bound what drift does — it is one-way, and hidden columns and inferred
 * relationships are not drift — are written down.
 */
class DriftDocsTests {

    /** Where the drift endpoints live; every documented path starts with this. */
    private static final String DRIFT_BASE = "/api/graph/drift";

    private static final String DRIFT_HEADING = "## Drift detection";

    /** A JSON body passed to curl with {@code -d '...'} on the line after the URL. */
    private static final Pattern CURL_BODY = Pattern.compile("-d\\s+'(\\{.*?})'");

    /** Any URL a README curl example points at. */
    private static final Pattern URL = Pattern.compile("https?://[^\\s'\"]+");

    private final ObjectMapper json = new ObjectMapper();

    private final String readme;

    DriftDocsTests() throws IOException {
        readme = Files.readString(Path.of("README.md"));
    }

    @Test
    void documentsEveryDriftEndpointTheControllerMaps() {
        List<String> routes = mappedRoutes();
        assertThat(routes).containsExactlyInAnyOrder(
                "GET " + DRIFT_BASE,
                "GET " + DRIFT_BASE + "/{source}",
                "POST " + DRIFT_BASE + "/{source}/accept",
                "POST " + DRIFT_BASE + "/{source}/renames");
        String drift = driftSection();
        for (String route : routes) {
            // "GET /api/graph/drift/{source}" style: method, then the template.
            String method = route.substring(0, route.indexOf(' '));
            String template = route.substring(route.indexOf(' ') + 1);
            assertThat(drift)
                    .as("documented endpoint heading for %s", route)
                    .contains(method + " " + template);
            assertThat(urlsIn(drift))
                    .as("curl example for %s", route)
                    .anySatisfy(url -> assertThat(url).matches(urlPatternFor(template)));
        }

        // Nothing may document a drift URL the controller does not answer.
        String answered = "(" + routes.stream()
                .map(route -> pathPatternFor(route.substring(route.indexOf(' ') + 1)))
                .collect(java.util.stream.Collectors.joining("|")) + ")";
        for (String url : urlsIn(drift)) {
            if (!url.contains(DRIFT_BASE)) {
                continue;
            }
            assertThat(pathOf(url))
                    .as("documented URL %s maps to a real drift route", url)
                    .matches(answered);
        }
    }

    @Test
    void documentedQueryParametersAndBodyFieldsExistInCode() {
        Set<String> parameters = new LinkedHashSet<>();
        for (Method method : DriftController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                RequestParam requested = parameter.getAnnotation(RequestParam.class);
                if (requested != null && !requested.name().isEmpty()) {
                    parameters.add(requested.name());
                }
            }
        }
        assertThat(parameters).contains("check");

        // Only a parameter the controller reads may appear in a drift example.
        for (String url : urlsIn(driftSection())) {
            int question = url.indexOf('?');
            if (question < 0) {
                continue;
            }
            for (String pair : url.substring(question + 1).split("&")) {
                String name = pair.split("=", 2)[0];
                assertThat(parameters)
                        .as("query parameter '%s' in %s", name, url)
                        .contains(name);
            }
        }

        assertThat(componentNames(DriftController.AcceptRequest.class))
                .containsExactly("fingerprint");
        assertThat(bodyKeysAfter("#### `POST " + DRIFT_BASE + "/{source}/accept`"))
                .containsExactlyInAnyOrderElementsOf(
                        componentNames(DriftController.AcceptRequest.class));

        assertThat(componentNames(DriftController.RenameRequest.class))
                .containsExactly("from", "to");
        assertThat(bodyKeysAfter("#### `POST " + DRIFT_BASE + "/{source}/renames`"))
                .containsExactlyInAnyOrderElementsOf(
                        componentNames(DriftController.RenameRequest.class));
    }

    @Test
    void theReportedJsonExampleUsesTheRealRecordComponents() throws IOException {
        String drift = driftSection();
        String example = fencedBlock(drift, "```json");
        JsonNode report = json.readTree(example);

        assertThat(fieldNames(report))
                .as("DriftReport fields in the README example")
                .containsExactlyInAnyOrderElementsOf(componentNames(DriftReport.class));
        assertThat(enumerationNames(DriftReport.Status.class))
                .as("status %s is a real one", report.path("status").asText())
                .contains(report.path("status").asText());

        JsonNode changes = report.path("changes");
        assertThat(changes.isArray()).isTrue();
        for (JsonNode change : changes) {
            assertThat(fieldNames(change))
                    .as("SchemaChange fields in the README example")
                    .containsExactlyInAnyOrderElementsOf(componentNames(SchemaChange.class));
            assertThat(enumerationNames(SchemaChange.Type.class))
                    .contains(change.path("type").asText());
            assertThat(enumerationNames(SchemaChange.Severity.class))
                    .contains(change.path("severity").asText());
        }

        JsonNode impact = report.path("impact");
        assertThat(impact.isArray()).isTrue();
        assertThat(impact.size())
                .as("one impact entry per change")
                .isEqualTo(changes.size());
        for (JsonNode entry : impact) {
            assertThat(fieldNames(entry))
                    .as("ChangeImpact fields in the README example")
                    .containsExactlyInAnyOrderElementsOf(componentNames(ChangeImpact.class));
            assertThat(fieldNames(entry.path("change")))
                    .as("the change an impact entry names")
                    .containsExactlyInAnyOrderElementsOf(componentNames(SchemaChange.class));
        }
    }

    @Test
    void documentsEveryStatusTypeAndSeverity() {
        String drift = driftSection();
        for (DriftReport.Status status : DriftReport.Status.values()) {
            assertThat(drift)
                    .as("status %s", status)
                    .contains(status.wireName());
        }
        for (SchemaChange.Type type : SchemaChange.Type.values()) {
            assertThat(drift).as("change type %s", type).contains(type.name());
        }
        for (SchemaChange.Severity severity : SchemaChange.Severity.values()) {
            assertThat(drift).as("severity %s", severity).contains(severity.name());
        }

        // The rename thresholds are the ones the diff uses.
        assertThat(SnapshotDiff.NAME_SIMILARITY_THRESHOLD).isEqualTo(0.6);
        assertThat(SnapshotDiff.ENTITY_COLUMN_OVERLAP_THRESHOLD).isEqualTo(0.8);
        assertThat(drift).contains("0.6").contains("0.8");
    }

    @Test
    void documentsBaselinesSnapshotsAndTheirDefaults() {
        SchemaToEntityProperties defaults = new SchemaToEntityProperties();
        assertThat(defaults.getSnapshotDir()).isEqualTo("./snapshots");
        assertThat(defaults.getDriftCheckInterval()).isNull();

        assertThat(readme).contains("schema-to-entity.snapshot-dir");
        assertThat(readme).contains("schema-to-entity.drift-check-interval");
        String drift = driftSection();
        assertThat(drift).contains("snapshot-dir").contains("./snapshots");
        assertThat(drift).contains("drift-check-interval");
        assertThat(drift).contains(DRIFT_BASE + "/{source}");

        // Where the files live, the history folder it keeps, and the name rule
        // that stops a source name escaping the folder.
        assertThat(drift).contains("<snapshot-dir>/<source>.json");
        assertThat(drift).contains("history/<source>");
        assertThat(drift).contains("[a-z0-9-]{1,40}");
    }

    @Test
    void documentsThatDriftIsOneWayAndNeverWritesToADatabase() {
        String drift = driftSection();
        assertThat(drift).contains("one-way: database \u2192 JSON");
        assertThat(drift).contains("ever sends SQL to a source database");
        assertThat(drift).contains("writes to a source database");
        assertThat(drift).contains("your own migration tooling");
        // The files drift may write: snapshots, and the overlay for a rename.
        assertThat(drift).contains("The only files written are the baseline snapshots");
        assertThat(drift).contains("The only file written is the overlay");
    }

    @Test
    void documentsWhatDoesNotCountAsDrift() {
        String drift = driftSection();
        assertThat(drift).contains("What is *not* drift");
        // Hidden columns: filtered during discovery, so absent on both sides.
        assertThat(drift).contains("**Hidden columns.**");
        assertThat(drift).contains("hidden-columns");
        // Inferred relationships: dropped before fingerprinting.
        assertThat(drift).contains("**Inferred relationships.**");
        assertThat(drift).contains("never reach a snapshot");
        // Overlay edits and data changes are not structure.
        assertThat(drift).contains("**Overlay edits.**");
        assertThat(drift).contains("**Data.**");
    }

    @Test
    void documentsTheReviewThenAcceptWorkflow() {
        String drift = driftSection();
        assertThat(drift).contains("review-then-accept workflow");
        assertThat(drift).contains("renames");
        assertThat(drift).contains("accept");
        // The three ways an accept or a rename can be refused.
        for (String status : List.of("400", "404", "409")) {
            assertThat(drift).as("status %s", status).contains(status);
        }
        // Reading the report and accepting it are keyed to the same fingerprint.
        assertThat(drift).contains("currentFingerprint");
    }

    @Test
    void documentsTheSchemaVersionHeaderForUis() {
        String drift = driftSection();
        assertThat(SchemaVersion.LENGTH).isEqualTo(12);
        assertThat(drift).contains("X-Schema-Version");
        assertThat(drift).contains("first 12 characters");
        assertThat(drift).contains("schemaVersion");
        // The version and the fingerprint are the same number, cut differently.
        assertThat(drift).contains("truncated to 12 characters");
    }

    @Test
    void documentsThatTheEndpointsAreUnauthenticatedAdminEndpoints() {
        String drift = driftSection();
        assertThat(drift).contains("**Security:**");
        assertThat(drift).contains("no CORS");
        assertThat(drift).contains("no authentication of their own");
        assertThat(drift).contains("graph-enabled");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Every route the controller answers, as {@code "GET /api/graph/drift/{source}"}.
     * Built from the mapping annotations, so the README cannot drift from the code.
     */
    private static List<String> mappedRoutes() {
        RequestMapping root = DriftController.class.getAnnotation(RequestMapping.class);
        assertThat(root).isNotNull();
        String base = root.value()[0];
        assertThat(base).isEqualTo(DRIFT_BASE);

        List<String> routes = new ArrayList<>();
        for (Method method : DriftController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (get != null) {
                routes.add("GET" + " " + base + pathOf(get));
            } else if (post != null) {
                routes.add("POST" + " " + base + pathOf(post));
            }
        }
        return routes;
    }

    /** The method's mapped path, empty when it is mapped on the base path itself. */
    private static String pathOf(Annotation mapping) {
        String[] value = mapping instanceof GetMapping get
                ? get.value()
                : ((PostMapping) mapping).value();
        return value.length == 0 || value[0].isEmpty() ? "" : value[0];
    }

    /**
     * A URL regex for a path template: a host, then the template with every
     * {@code {placeholder}} filled by one path segment, then an optional query.
     */
    private static String urlPatternFor(String template) {
        return "^https?://[^/]+" + pathPatternFor(template) + "(\\?[^\\s]*)?$";
    }

    /** The same template as a path-only regex, anchored at both ends. */
    private static String pathPatternFor(String template) {
        StringBuilder regex = new StringBuilder();
        int from = 0;
        while (true) {
            int open = template.indexOf('{', from);
            if (open < 0) {
                return regex.append(Pattern.quote(template.substring(from))).toString();
            }
            regex.append(Pattern.quote(template.substring(from, open))).append("[^/]+");
            from = template.indexOf('}', open) + 1;
        }
    }

    /** The path a documented URL points at, without the host and without a query. */
    private static String pathOf(String url) {
        String withoutQuery = url.replaceFirst("\\?[^\\s]*$", "");
        int scheme = withoutQuery.indexOf("://");
        int pathStart = withoutQuery.indexOf('/', scheme + 3);
        return pathStart < 0 ? "" : withoutQuery.substring(pathStart);
    }

    /** The README's drift documentation, up to the next top-level section. */
    private String driftSection() {
        int from = readme.indexOf(DRIFT_HEADING);
        assertThat(from).as("the README documents drift").isGreaterThan(-1);
        int next = readme.indexOf("\n## ", from + DRIFT_HEADING.length());
        return readme.substring(from, next < 0 ? readme.length() : next);
    }

    private static List<String> urlsIn(String section) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL.matcher(section);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        assertThat(urls).as("the drift section has curl examples").isNotEmpty();
        return urls;
    }

    /** The keys of the JSON body in the first curl example after a heading. */
    private List<String> bodyKeysAfter(String heading) {
        String drift = driftSection();
        int from = drift.indexOf(heading);
        assertThat(from).as("heading %s", heading).isGreaterThan(-1);
        Matcher matcher = CURL_BODY.matcher(drift.substring(from));
        assertThat(matcher.find()).as("a JSON body for %s", heading).isTrue();
        try {
            return fieldNames(json.readTree(matcher.group(1)));
        } catch (IOException e) {
            throw new IllegalStateException("undocumented curl body for " + heading, e);
        }
    }

    /** The first fenced block after the marker line in the drift section. */
    private static String fencedBlock(String section, String opener) {
        int from = section.indexOf(opener);
        assertThat(from).as("%s block in the drift section", opener).isGreaterThan(-1);
        int start = section.indexOf('\n', from) + 1;
        int end = section.indexOf("```", start);
        assertThat(end).as("closed %s block", opener).isGreaterThan(start);
        return section.substring(start, end);
    }

    /** The JSON field names of an object node, in document order. */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The record component names of a type. */
    private static Set<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** The names an enum uses in JSON: {@code @JsonValue} spellings for statuses. */
    private static Set<String> enumerationNames(Class<? extends Enum<?>> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Object value : type.getEnumConstants()) {
            names.add(value instanceof DriftReport.Status status
                    ? status.wireName()
                    : ((Enum<?>) value).name());
        }
        return names;
    }
}
