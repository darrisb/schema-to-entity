package com.example.dynamicmeta.pages;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Admin endpoints for stored visual UI page documents. */
@RestController
@RequestMapping("/api/pages")
public final class PageController {

    private static final String STORAGE_ERROR = "Page storage failed";

    private final PageStore store;
    private final PageDependencyExtractor dependencyExtractor;
    private final PageStatusService statusService;

    public PageController(PageStore store, PageDependencyExtractor dependencyExtractor) {
        this(store, dependencyExtractor, new PageStatusService(null, dependencyExtractor));
    }

    public PageController(PageStore store, PageDependencyExtractor dependencyExtractor,
                          PageStatusService statusService) {
        this.store = store;
        this.dependencyExtractor = dependencyExtractor;
        this.statusService = statusService;
    }

    @GetMapping
    public List<PageSummary> list() {
        List<Page> pages = store.list();
        List<PageStatus> statuses = statusService.statuses(pages);
        return java.util.stream.IntStream.range(0, pages.size())
                .mapToObj(index -> {
                    Page page = pages.get(index);
                    return new PageSummary(page.id(), page.name(), page.description(),
                            page.updatedAt(), dependencyExtractor.extract(page.document()).entities(),
                            statuses.get(index));
                })
                .toList();
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        return store.get(id)
                .<ResponseEntity<?>>map(page -> ResponseEntity.ok(statusService.status(page)))
                .orElseGet(() -> notFound(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return store.get(id)
                .<ResponseEntity<?>>map(page -> ResponseEntity.ok(detail(page)))
                .orElseGet(() -> notFound(id));
    }

    @PutMapping("/{id}")
    public Page put(@PathVariable String id, @RequestBody SavePageRequest request) {
        return store.save(new Page(id, request.name(), request.description(),
                request.document(), null, null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!store.delete(id)) {
            return notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(error(exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableDocument(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(error("Invalid page document"));
    }

    @ExceptionHandler({UncheckedIOException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> storageFailure(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(STORAGE_ERROR));
    }

    private PageDetail detail(Page page) {
        return new PageDetail(page.id(), page.name(), page.description(), page.document(),
                page.createdAt(), page.updatedAt(), dependencyExtractor.extract(page.document()));
    }

    private static ResponseEntity<Map<String, String>> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("Unknown page: " + id));
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    public record SavePageRequest(String name, String description, JsonNode document) {
    }

    public record PageSummary(String id, String name, String description, Instant updatedAt,
                              Set<String> entities, PageStatus status) {
    }

    public record PageDetail(String id, String name, String description, JsonNode document,
                             Instant createdAt, Instant updatedAt,
                             PageDependencies dependencies) {
    }
}
