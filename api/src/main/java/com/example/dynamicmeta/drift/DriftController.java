package com.example.dynamicmeta.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.example.dynamicmeta.connection.SecretRedactor;
import com.example.dynamicmeta.data.SourceNotFoundException;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.graph.GraphIds;
import com.example.dynamicmeta.graph.OverlayStore;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DR-007: admin endpoints for the drift workflow, exposed next to the graph
 * endpoints under {@code /api/graph/drift}. These are <em>not</em> data API
 * endpoints: they report on every configured source and accept review
 * decisions, so they get no CORS mapping — only {@code /api/data/**} does —
 * and host applications should keep them behind authentication and
 * authorization, exactly like {@link com.example.dynamicmeta.graph.GraphController}.
 * <p>
 * A report is what {@link DriftService} last remembered for a source, so
 * reading drift is free; {@code ?check=true} runs a fresh check first, and a
 * source that has never been checked is checked now, which is what records its
 * baseline. Errors are always {@code {"error": ...}}: an unknown source is
 * 404, invalid input — a blank fingerprint or a rename id that does not belong
 * to the named source — is 400, and an acceptance whose fingerprint no longer
 * matches what the last check showed, or that comes before any check, is 409.
 * <p>
 * A rename is how a confirmed {@link DriftReport.Status#CHANGED} report stops
 * showing the reviewer's edits as lost: it moves every overlay edit made for
 * an entity or column id of one source to its new id through
 * {@link OverlayStore#renameId}. Nothing here writes to a source database:
 * a check only reads metadata, and the only files written are the baseline
 * snapshots and the overlay.
 */
@RestController
@RequestMapping("/api/graph/drift")
public class DriftController {

    private final DriftService driftService;
    private final SourceRegistry registry;
    private final OverlayStore overlayStore;

    public DriftController(DriftService driftService, SourceRegistry registry,
                           OverlayStore overlayStore) {
        this.driftService = driftService;
        this.registry = registry;
        this.overlayStore = overlayStore;
    }

    /**
     * The last report of every configured source, in name order. A source
     * that has never been checked is checked now, so its first report is a
     * {@link DriftReport.Status#BASELINE_CREATED}; {@code check=true} runs a
     * fresh check of every source first instead of serving remembered reports.
     */
    @GetMapping
    public List<DriftReport> reports(
            @RequestParam(name = "check", defaultValue = "false") boolean check) {
        List<DriftReport> reports = new ArrayList<>();
        for (String source : new TreeSet<>(registry.names())) {
            reports.add(check ? driftService.check(source) : lastOrCheck(source));
        }
        return reports;
    }

    /**
     * The last report of one source, checked now when it never has been or
     * when {@code check=true} asks for a fresh check. An unknown source
     * is 404.
     */
    @GetMapping("/{source}")
    public DriftReport report(
            @PathVariable("source") String source,
            @RequestParam(name = "check", defaultValue = "false") boolean check) {
        return check ? driftService.check(source) : lastOrCheck(source);
    }

    /**
     * Accepts the snapshot the fingerprint names as the source's new baseline
     * and returns the fresh report that follows — {@code unchanged}, because
     * the baseline now matches the schema. The fingerprint must be the one the
     * last check showed: a stale one means the schema moved since the
     * reviewer read the report, and accepting then would accept something
     * else. Both that and accepting before any check return 409.
     */
    @PostMapping("/{source}/accept")
    public DriftReport accept(@PathVariable("source") String source,
                              @RequestBody AcceptRequest request) {
        if (request == null || request.fingerprint() == null
                || request.fingerprint().isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        driftService.accept(source, request.fingerprint().trim());
        return driftService.check(source);
    }

    /**
     * Moves every overlay edit made for an entity or column id of this source
     * to its new id — how a confirmed rename carries the reviewer's labels
     * and decisions over. Both ids must be well-formed entity or column ids
     * of the named source, or the request is 400; the source itself must be
     * configured, or it is 404. The only file written is the overlay.
     * Returns 204.
     */
    @PostMapping("/{source}/renames")
    public ResponseEntity<?> rename(@PathVariable("source") String source,
                                    @RequestBody RenameRequest request) {
        requireConfigured(source);
        String from = idOfSource(request == null ? null : request.from(), "from", source);
        String to = idOfSource(request == null ? null : request.to(), "to", source);
        overlayStore.renameId(from, to);
        return ResponseEntity.noContent().build();
    }

    /** The fingerprint the reviewer was shown and now confirms. */
    public record AcceptRequest(String fingerprint) {
    }

    /** The old and new entity or column ids of one confirmed rename. */
    public record RenameRequest(String from, String to) {
    }

    @ExceptionHandler(SourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> unknownSource(
            SourceNotFoundException exception) {
        return notFound(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(error(exception.getMessage()));
    }

    /**
     * The conflict every refusal to accept answers with: the fingerprint is
     * no longer the one last checked, or nothing has been checked yet. The
     * reviewer should read the newer report and accept that one.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(
            IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(exception.getMessage()));
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /** The remembered report of a source, or a fresh check when it has none. */
    private DriftReport lastOrCheck(String source) {
        return driftService.lastReport(source).orElseGet(() -> driftService.check(source));
    }

    /**
     * A rename id of the named source: a well-formed entity or column id
     * whose source part is the source in the path. Ids travel normalized —
     * trimmed and lower-cased — exactly like the graph's ids. A relationship
     * id, a blank or malformed id, or one naming another source is a client
     * error, not a missing thing: 400.
     */
    private static String idOfSource(String value, String what, String source) {
        String id = requiredId(value, what);
        try {
            GraphIds.ColumnId column = GraphIds.parseColumnId(id);
            // Rebuild it so a part that carries a separator — a relationship
            // id parses as a column whose name is "x->sales:b.y" — fails here.
            String checked = GraphIds.columnId(column.source(), column.entity(), column.column());
            return sameSource(checked, column.source(), what, source);
        } catch (IllegalArgumentException notColumn) {
            // Not a column id: an entity id is the only other shape.
        }
        GraphIds.EntityId entity = GraphIds.parseEntityId(id);
        String checked = GraphIds.entityId(entity.source(), entity.entity());
        return sameSource(checked, entity.source(), what, source);
    }

    /** The id, once its source part is proven to be the named source. */
    private static String sameSource(String id, String idSource, String what, String source) {
        if (!idSource.equals(source.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    what + " must belong to source '" + source + "': " + id);
        }
        return id;
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

    /** Rejects a name that is not configured before the overlay is touched. */
    private void requireConfigured(String source) {
        if (source == null || !registry.names().contains(source)) {
            throw new SourceNotFoundException(source);
        }
    }

    /** A 404 with the {@code {"error": ...}} body every endpoint uses. */
    private static ResponseEntity<Map<String, String>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(message));
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", SecretRedactor.redact(message));
    }
}
