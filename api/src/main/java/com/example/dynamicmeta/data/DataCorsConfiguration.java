package com.example.dynamicmeta.data;

import java.time.Duration;
import java.util.List;

import com.example.dynamicmeta.SchemaToEntityProperties;

import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * DA-007: browser access to the read-only data API.
 * <p>
 * Registers CORS for {@code /api/data/**} only. The host application's own CORS
 * configuration is left untouched because this is a path-scoped mapping added
 * through a {@link WebMvcConfigurer}, not a global filter or cross-origin
 * annotation on the admin endpoints.
 * <p>
 * An empty {@code schema-to-entity.data-allowed-origins} list registers no
 * mapping at all, so no CORS headers are sent. The literal {@code *} is the
 * only wildcard and must be written explicitly; every other entry is matched
 * exactly. Only GET and OPTIONS are allowed, credentials are never sent, and
 * the preflight response is cached for one hour.
 * <p>
 * DR-008: {@value DataController#SCHEMA_VERSION_HEADER} is registered as an
 * exposed header, so a cross-origin browser can read the schema version each
 * successful data response carries.
 */
public class DataCorsConfiguration implements WebMvcConfigurer {

    static final String DATA_API_PATTERN = "/api/data/**";
    static final long MAX_AGE_SECONDS = Duration.ofHours(1).getSeconds();

    private final List<String> allowedOrigins;

    public DataCorsConfiguration(SchemaToEntityProperties properties) {
        List<String> configured = properties.getDataAllowedOrigins();
        this.allowedOrigins = configured == null ? List.of() : List.copyOf(configured);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        CorsRegistration registration = registry.addMapping(DATA_API_PATTERN);
        if (allowedOrigins.contains("*")) {
            // Explicit wildcard. Credentials stay off, which is what makes the
            // literal "*" acceptable to Spring's CORS validation.
            registration.allowedOrigins("*");
        } else {
            registration.allowedOrigins(allowedOrigins.toArray(new String[0]));
        }
        registration.allowedMethods("GET", "OPTIONS")
                .allowCredentials(false)
                .maxAge(MAX_AGE_SECONDS)
                // DR-008: the schema version travels on every data response.
                .exposedHeaders(DataController.SCHEMA_VERSION_HEADER);
    }
}
