package com.example.dynamicmeta.pages;

import java.time.Duration;
import java.util.List;

import com.example.dynamicmeta.SchemaToEntityProperties;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Browser access scoped to the page document endpoints. */
public class PageCorsConfiguration implements WebMvcConfigurer {

    static final String PAGE_API_PATTERN = "/api/pages/**";
    static final long MAX_AGE_SECONDS = Duration.ofHours(1).getSeconds();

    private final List<String> allowedOrigins;

    public PageCorsConfiguration(SchemaToEntityProperties properties) {
        List<String> configured = properties.getPageAllowedOrigins();
        this.allowedOrigins = configured == null ? List.of() : List.copyOf(configured);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        CorsRegistration registration = registry.addMapping(PAGE_API_PATTERN);
        if (allowedOrigins.contains("*")) {
            registration.allowedOrigins("*");
        } else {
            registration.allowedOrigins(allowedOrigins.toArray(new String[0]));
        }
        registration.allowedMethods("GET", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(MAX_AGE_SECONDS);
    }
}
