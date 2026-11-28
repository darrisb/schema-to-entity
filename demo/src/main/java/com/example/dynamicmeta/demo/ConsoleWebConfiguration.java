package com.example.dynamicmeta.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CN-009: serve the admin console from the API on its own origin.
 *
 * <p>The Docker build runs {@code npm run build} in {@code console/} (see the
 * console stage in the root Dockerfile) and drops the Vite output into the jar
 * at {@code classpath:/static/console/}. Spring Boot then serves
 * {@code /console/index.html} and the {@code /console/assets/**} files the
 * bundle references through its normal static-resource handling.
 *
 * <p>Boot only maps a bundled {@code index.html} to {@code "/"} as a welcome
 * page, so this config adds the two plain mappings the console needs:
 * {@code /console} redirects to {@code /console/} (the canonical directory URL
 * the build's {@code base: "/console/"} assumes) and {@code /console/} forwards
 * to the index page. No CORS is registered: the console calls {@code /api/**}
 * on the same origin, so none is needed. Nothing else changes.
 */
@Configuration
public class ConsoleWebConfiguration implements WebMvcConfigurer {

    /** Directory URL of the console; forwards to the bundled index page. */
    static final String CONSOLE_PATH = "/console/";

    /** Extensionless URL; redirects to {@value #CONSOLE_PATH}. */
    static final String CONSOLE_SHORT_PATH = "/console";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController(CONSOLE_SHORT_PATH)
                .setViewName("redirect:" + CONSOLE_PATH);
        registry.addViewController(CONSOLE_PATH)
                .setViewName("forward:/console/index.html");
    }
}
