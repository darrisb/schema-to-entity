package com.example.dynamicmeta.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal host application that consumes the schema-to-entity starter.
 *
 * <p>The starter discovers the {@code DataSource} this application configures and exposes
 * the {@code /api/schema} endpoints. Nothing from the starter is declared here on purpose, so
 * the demo also proves the auto-configuration works without manual bean wiring.
 */
@SpringBootApplication(scanBasePackages = "com.example.dynamicmeta.demo")
public class SchemaToEntityDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemaToEntityDemoApplication.class, args);
    }
}
