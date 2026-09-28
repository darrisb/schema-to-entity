package com.example.dynamicmeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/entities")
public class DynamicEntityController {

    private record IdInfo(String property, String type) {
    }

    private record RelInfo(String targetEntity) {
    }

    private final DynamicEntityService service;
    private final Set<String> knownEntities;
    private final Map<String, IdInfo> idByEntity;
    private final Map<String, Map<String, String>> typesByEntity = new LinkedHashMap<>();
    private final Map<String, Map<String, RelInfo>> relsByEntity = new LinkedHashMap<>();

    public DynamicEntityController(
            DynamicEntityService service,
            ObjectMapper objectMapper,
            @Value("classpath:spring-entities-config.json") Resource configResource) {
        this.service = service;
        this.knownEntities = new LinkedHashSet<>();
        this.idByEntity = new LinkedHashMap<>();
        try (InputStream in = configResource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            for (JsonNode entity : root.path("entities")) {
                String name = entity.path("entityName").asText();
                knownEntities.add(name);
                Map<String, String> types = typesByEntity.computeIfAbsent(name, k -> new LinkedHashMap<>());
                JsonNode pk = entity.path("primaryKey");
                if (!pk.isMissingNode() && !pk.path("composite").asBoolean(false)) {
                    idByEntity.put(name, new IdInfo(
                            pk.path("name").asText(),
                            pk.path("type").asText()));
                    types.put(pk.path("name").asText(), pk.path("type").asText());
                }
                for (JsonNode p : entity.path("properties")) {
                    types.put(p.path("name").asText(), p.path("type").asText());
                }
                Map<String, RelInfo> rels = relsByEntity.computeIfAbsent(name, k -> new LinkedHashMap<>());
                for (JsonNode r : entity.path("relationships")) {
                    if ("many-to-one".equals(r.path("type").asText())) {
                        rels.put(r.path("name").asText(), new RelInfo(r.path("targetEntity").asText()));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read spring-entities-config.json", e);
        }
    }

    @GetMapping
    public Set<String> entityNames() {
        return knownEntities;
    }

    @GetMapping("/{entityName}")
    public List<Map<String, Object>> findAll(@PathVariable String entityName) {
        requireKnown(entityName);
        return service.findAll(entityName).stream().map(DynamicEntityController::flatten).toList();
    }

    @GetMapping("/{entityName}/{id}")
    public ResponseEntity<Map<String, Object>> findById(
            @PathVariable String entityName, @PathVariable String id) {
        requireKnown(entityName);
        IdInfo info = idByEntity.get(entityName);
        if (info == null) {
            throw new IllegalArgumentException("Entity " + entityName + " has a composite id; "
                    + "query it with GET /api/entities/" + entityName + " and filter client-side");
        }
        Object parsedId = parseId(id, info.type());
        Map<String, Object> found = service.findById(entityName, info.property(), parsedId);
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(flatten(found));
    }

    @PostMapping("/{entityName}")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String entityName, @RequestBody Map<String, Object> values) {
        requireKnown(entityName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flatten(service.persist(entityName, sanitize(entityName, new LinkedHashMap<>(values)))));
    }

    private Map<String, Object> sanitize(String entityName, Map<String, Object> values) {
        Map<String, String> types = typesByEntity.getOrDefault(entityName, Map.of());
        Map<String, RelInfo> rels = relsByEntity.getOrDefault(entityName, Map.of());
        values.replaceAll((key, value) -> {
            if (rels.containsKey(key) && value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> asMap = new LinkedHashMap<>((Map<String, Object>) nested);
                asMap.put("$type$", rels.get(key).targetEntity());
                return sanitize(rels.get(key).targetEntity(), asMap);
            }
            if (value instanceof Integer i) {
                return switch (types.getOrDefault(key, "")) {
                    case "java.lang.Long" -> i.longValue();
                    case "java.lang.Float" -> i.floatValue();
                    case "java.lang.Double" -> i.doubleValue();
                    default -> value;
                };
            }
            return value;
        });
        return values;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private void requireKnown(String entityName) {
        if (!knownEntities.contains(entityName)) {
            throw new IllegalArgumentException("Unknown dynamic entity: " + entityName
                    + "; known: " + knownEntities);
        }
    }

    private static Object parseId(String id, String type) {
        return switch (type) {
            case "java.lang.Long" -> Long.valueOf(id);
            case "java.lang.Integer" -> Integer.valueOf(id);
            case "java.lang.Short" -> Short.valueOf(id);
            default -> id;
        };
    }

    private static Map<String, Object> flatten(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        row.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> asMap = (Map<String, Object>) nested;
                out.put(key, flatten(asMap));
            } else if (value instanceof java.util.Collection<?>) {
                skipped.add(key);
            } else {
                out.put(key, value);
            }
        });
        if (!skipped.isEmpty()) {
            out.put("_lazyCollections", skipped);
        }
        return out;
    }
}
