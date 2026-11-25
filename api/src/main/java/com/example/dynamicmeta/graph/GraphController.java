package com.example.dynamicmeta.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.dynamicmeta.connection.SecretRedactor;
import com.example.dynamicmeta.data.SourceNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GR-007: admin endpoints for the combined {@link SchemaGraph} document and
 * its {@link Overlay} edits. These are <em>not</em> data API endpoints: they
 * name every configured source and accept edits, so they get no CORS
 * mapping — only {@code /api/data/**} does — and host applications should
 * keep them behind authentication and authorization.
 * <p>
 * Every id travels in a body or a query parameter, never a path segment:
 * ids contain {@code :} and {@code ->}, which a path segment cannot carry
 * unambiguously. Errors are always {@code {"error": ...}} — an unknown id
 * is 404, invalid input is 400. Nothing here writes to any source database;
 * the only file written is the overlay JSON, through {@link OverlayStore}.
 * <p>
 * The document served is the built graph with the overlay applied, so every
 * read reflects the stored edits. Id validation is against the built graph
 * before the overlay plus the overlay's manual relationships, so an id
 * whose relationship was rejected — or whose edit is an orphan in the
 * overlay — can still be named when clearing or replacing a decision, and a
 * manual relationship is decided like any other relationship of the
 * document.
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final SchemaGraphBuilder builder;
    private final OverlayStore overlayStore;

    public GraphController(SchemaGraphBuilder builder, OverlayStore overlayStore) {
        this.builder = builder;
        this.overlayStore = overlayStore;
    }

    /**
     * The combined document: the built graph with the overlay applied.
     * Rejected relationships are hidden unless {@code includeRejected} is
     * true; {@code sources} is a comma-separated list limiting the build, or
     * empty for every configured source.
     */
    @GetMapping
    public SchemaGraph graph(
            @RequestParam(name = "sources", required = false) String sources,
            @RequestParam(name = "includeRejected", defaultValue = "false")
            boolean includeRejected) {
        return OverlayApplier.apply(builder.build(sourceNames(sources)),
                overlayStore.overlay(), includeRejected);
    }

    /**
     * The document's relationships, optionally filtered by status
     * ({@code declared}, {@code proposed}, {@code confirmed} or
     * {@code rejected}). Rejected relationships are kept by default here, so
     * {@code status=rejected} can list them; pass
     * {@code includeRejected=false} to hide them.
     */
    @GetMapping("/relationships")
    public List<GraphRelationship> relationships(
            @RequestParam(name = "sources", required = false) String sources,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "includeRejected", defaultValue = "true")
            boolean includeRejected) {
        SchemaGraph graph = OverlayApplier.apply(builder.build(sourceNames(sources)),
                overlayStore.overlay(), includeRejected);
        if (status == null || status.isBlank()) {
            return graph.relationships();
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!isStatus(normalized)) {
            throw new IllegalArgumentException("Unknown relationship status: " + status);
        }
        List<GraphRelationship> matching = new ArrayList<>();
        for (GraphRelationship relationship : graph.relationships()) {
            if (normalized.equals(relationship.status())) {
                matching.add(relationship);
            }
        }
        return matching;
    }

    /**
     * Confirms or rejects a relationship that is currently in the document,
     * or clears its decision when {@code status} is null. Returns 204.
     */
    @PutMapping("/overlay/relationships")
    public ResponseEntity<?> decideRelationship(
            @RequestBody RelationshipDecision decision) {
        String id = requiredId(decision.id(), "id");
        if (!relationshipIds().contains(id)) {
            return notFound("Unknown relationship: " + id);
        }
        if (decision.status() == null || decision.status().isBlank()) {
            overlayStore.clearDecision(id);
        } else {
            overlayStore.decide(id, decision.status());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Asserts a manual relationship between two column ids that both exist
     * in the document, and returns its relationship id. Asserting a pair
     * that is already asserted changes nothing.
     */
    @PostMapping("/overlay/relationships")
    public ResponseEntity<Map<String, String>> addManualRelationship(
            @RequestBody ManualRelationshipRequest request) {
        String from = columnEnd(request.from(), "from");
        String to = columnEnd(request.to(), "to");
        Set<String> columns = columnIds();
        if (!columns.contains(from)) {
            return notFound("Unknown column: " + from);
        }
        if (!columns.contains(to)) {
            return notFound("Unknown column: " + to);
        }
        overlayStore.addManual(from, to);
        return ResponseEntity.ok(Map.of("id", GraphIds.relationshipId(from, to)));
    }

    /**
     * Removes a manual relationship or a decision held for the relationship
     * id, or both when the overlay holds each. Returns 204, or 404 when the
     * overlay holds neither.
     */
    @DeleteMapping("/overlay/relationships")
    public ResponseEntity<?> removeRelationship(@RequestParam("id") String id) {
        String normalized = requiredId(id, "id");
        GraphIds.parseRelationshipId(normalized);
        Overlay overlay = overlayStore.overlay();
        boolean manual = false;
        for (Overlay.ManualRelationship entry : overlay.manualRelationships()) {
            if (GraphIds.relationshipId(entry.from(), entry.to()).equals(normalized)) {
                manual = true;
                break;
            }
        }
        boolean decision = overlay.relationshipDecisions().containsKey(normalized);
        if (!manual && !decision) {
            return notFound("Unknown relationship: " + normalized);
        }
        if (manual) {
            overlayStore.removeManual(normalized);
        }
        if (decision) {
            overlayStore.clearDecision(normalized);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Labels the entity or column id, replacing any previous label for it.
     * The description may be null; the label may not. Returns 204.
     */
    @PutMapping("/overlay/labels")
    public ResponseEntity<?> setLabel(@RequestBody LabelChange change) {
        String id = requiredId(change.id(), "id");
        if (!idExists(id)) {
            return notFound("Unknown entity or column: " + id);
        }
        overlayStore.setLabel(id, change.label(), change.description());
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes the label of the id. The id must exist in the document, or the
     * overlay must hold a label for it — an orphan's label is still
     * removable. Returns 204, or 404 when neither holds.
     */
    @DeleteMapping("/overlay/labels")
    public ResponseEntity<?> removeLabel(@RequestParam("id") String id) {
        String normalized = requiredId(id, "id");
        if (!idExists(normalized)
                && !overlayStore.overlay().labels().containsKey(normalized)) {
            return notFound("Unknown entity or column: " + normalized);
        }
        overlayStore.removeLabel(normalized);
        return ResponseEntity.noContent().build();
    }

    /**
     * One relationship review decision: {@code confirmed},
     * {@code rejected} or null to clear.
     */
    public record RelationshipDecision(String id, String status) {
    }

    /** The two column ids of a manual relationship to assert. */
    public record ManualRelationshipRequest(String from, String to) {
    }

    /** A label and optional description for one entity or column id. */
    public record LabelChange(String id, String label, String description) {
    }

    @ExceptionHandler(SourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> unknownSource(SourceNotFoundException exception) {
        return notFound(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(error(exception.getMessage()));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Splits a comma-separated {@code sources} parameter into source names;
     * null selects every configured source.
     */
    private Set<String> sourceNames(String sources) {
        if (sources == null || sources.isBlank()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : sources.split(",")) {
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        if (names.isEmpty()) {
            return null;
        }
        // A misspelled name is a client error, not an unavailable database.
        Set<String> configured = builder.sourceNames();
        for (String name : names) {
            if (!configured.contains(name)) {
                throw new SourceNotFoundException(name);
            }
        }
        return names;
    }

    /** A usable id: present, not blank, trimmed and lower-cased. */
    private static String requiredId(String id, String what) {
        if (id == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** One end of a manual relationship: a valid, normalized column id. */
    private static String columnEnd(String value, String what) {
        String trimmed = requiredId(value, what);
        GraphIds.ColumnId parts = GraphIds.parseColumnId(trimmed);
        return GraphIds.columnId(parts.source(), parts.entity(), parts.column());
    }

    private static boolean isStatus(String value) {
        return GraphRelationship.DECLARED.equals(value)
                || GraphRelationship.PROPOSED.equals(value)
                || GraphRelationship.CONFIRMED.equals(value)
                || GraphRelationship.REJECTED.equals(value);
    }

    /**
     * The relationship ids a decision may name: those of the built document,
     * before the overlay — so an id whose relationship was rejected, or
     * whose decision is an orphan, can still be named when clearing or
     * replacing it — plus the overlay's manual relationships, which exist
     * only in the overlay but are part of the served document.
     */
    private Set<String> relationshipIds() {
        Set<String> ids = new HashSet<>();
        for (GraphRelationship relationship : builder.build(null).relationships()) {
            ids.add(relationship.id());
        }
        for (Overlay.ManualRelationship manual : overlayStore.overlay().manualRelationships()) {
            ids.add(GraphIds.relationshipId(manual.from(), manual.to()));
        }
        return ids;
    }

    /** The column ids of the built document, before the overlay. */
    private Set<String> columnIds() {
        Set<String> ids = new HashSet<>();
        for (GraphEntity entity : builder.build(null).entities()) {
            for (GraphColumn column : entity.columns()) {
                ids.add(column.id());
            }
        }
        return ids;
    }

    /** Whether the id names an entity or a column of the built document. */
    private boolean idExists(String id) {
        for (GraphEntity entity : builder.build(null).entities()) {
            if (entity.id().equals(id)) {
                return true;
            }
            for (GraphColumn column : entity.columns()) {
                if (column.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A 404 with the {@code {"error": ...}} body every endpoint uses. */
    private static ResponseEntity<Map<String, String>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(message));
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", SecretRedactor.redact(message));
    }
}
