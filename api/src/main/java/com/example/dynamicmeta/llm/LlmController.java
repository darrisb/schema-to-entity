package com.example.dynamicmeta.llm;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.example.dynamicmeta.data.SourceNotFoundException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoint for the compact language-model schema summary. */
@RestController
@RequestMapping("/api/llm")
public final class LlmController {

    private final SchemaSummary summary;

    public LlmController(SchemaSummary summary) {
        this.summary = summary;
    }

    @GetMapping("/schema")
    public ResponseEntity<?> schema(@RequestParam(required = false) String sources,
                                    @RequestParam(defaultValue = "text") String format,
                                    @RequestParam(defaultValue = "false") boolean values) {
        SchemaSummary.Document document = summary.summarize(sourceNames(sources), values);
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(document);
        }
        if (!"text".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("format must be text or json");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(summary.text(document));
    }

    private static Set<String> sourceNames(String sources) {
        if (sources == null || sources.isBlank()) return null;
        Set<String> names = new LinkedHashSet<>();
        for (String source : sources.split(",")) {
            if (!source.isBlank()) names.add(source.trim());
        }
        return names.isEmpty() ? null : names;
    }

    @ExceptionHandler(SourceNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(SourceNotFoundException exception) {
        return ResponseEntity.status(404).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
