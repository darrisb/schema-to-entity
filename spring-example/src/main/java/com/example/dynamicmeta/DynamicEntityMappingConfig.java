package com.example.dynamicmeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers every entity described by spring-entities-config.json as a
 * Hibernate "dynamic model" entity. Each entity is backed by a java.util.Map
 * instead of a compiled .java class, so no bytecode generation is required.
 *
 * Usage after boot:
 *   emf.createEntityManager().find("DynamicUser", 1L)            -> Map<String,Object>
 *   emf.unwrap(Session.class).persist("DynamicUser", userMap)    -> INSERT INTO users ...
 */
@Configuration(proxyBeanMethods = false)
public class DynamicEntityMappingConfig {

    @Bean
    public SpringMetadataHbmReader springMetadataHbmReader(
            ObjectMapper objectMapper,
            @Value("classpath:spring-entities-config.json") Resource configResource) {
        return new SpringMetadataHbmReader(objectMapper, configResource);
    }

    @Bean(destroyMethod = "close")
    public EntityManagerFactory entityManagerFactory(
            DataSource dataSource,
            SpringMetadataHbmReader reader,
            @Value("${dynamic-entities.dialect:org.hibernate.dialect.PostgreSQLDialect}") String dialect,
            @Value("${dynamic-entities.ddl-auto:validate}") String ddlAuto) {

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.DIALECT, dialect)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, ddlAuto)
                .build();

        MetadataSources sources = new MetadataSources(registry);
        for (String hbmXml : reader.toHbmXmlDocuments()) {
            sources.addInputStream(
                    new ByteArrayInputStream(hbmXml.getBytes(StandardCharsets.UTF_8)));
        }

        Metadata metadata = sources.buildMetadata();
        SessionFactory sessionFactory = metadata.buildSessionFactory();
        return sessionFactory; // Hibernate 6's SessionFactory implements EntityManagerFactory
    }

    public static class SpringMetadataHbmReader {

        private final ObjectMapper objectMapper;
        private final Resource configResource;

        public SpringMetadataHbmReader(ObjectMapper objectMapper, Resource configResource) {
            this.objectMapper = objectMapper;
            this.configResource = configResource;
        }

        public List<String> toHbmXmlDocuments() {
            try {
                JsonNode root = objectMapper.readTree(configResource.getInputStream());
                List<String> documents = new ArrayList<>();
                for (JsonNode entity : root.path("entities")) {
                    documents.add(toHbmDocument(entity));
                }
                return documents;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read spring-entities-config.json", e);
            }
        }

        String toHbmDocument(JsonNode entity) {
            JsonNode pk = entity.path("primaryKey");
            if (pk.path("synthetic").asBoolean(false)) {
                throw new IllegalStateException("Entity " + entity.path("entityName").asText()
                        + " has no primary key; Hibernate requires an identifier for dynamic entities.");
            }

            StringBuilder x = new StringBuilder();
            x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
             .append("<!DOCTYPE hibernate-mapping PUBLIC \"-//Hibernate/Hibernate Mapping DTD 3.0//EN\" ")
             .append("\"http://www.hibernate.org/dtd/hibernate-mapping-3.0.dtd\">\n")
             .append("<hibernate-mapping>\n")
             .append("  <class entity-name=\"").append(esc(entity.path("entityName").asText()))
             .append("\" table=\"").append(esc(entity.path("tableName").asText())).append("\">\n");

            if (pk.path("composite").asBoolean(false)) {
                x.append("    <composite-id name=\"id\">\n");
                for (JsonNode col : pk.path("columns")) {
                    x.append("      <key-property name=\"").append(col.path("name").asText())
                     .append("\" column=\"").append(col.path("columnName").asText())
                     .append("\" type=\"").append(hibernateType(col)).append("\"/>\n");
                }
                x.append("    </composite-id>\n");
            } else {
                boolean explicitColumn = pk.hasNonNull("length") || pk.hasNonNull("columnSqlType");
                if (explicitColumn) {
                    x.append("    <id name=\"").append(pk.path("name").asText())
                     .append("\" type=\"").append(hibernateType(pk)).append("\">\n")
                     .append("      <column name=\"").append(pk.path("columnName").asText(pk.path("name").asText())).append("\"");
                    if (pk.hasNonNull("length")) {
                        x.append(" length=\"").append(pk.path("length").asInt()).append("\"");
                    }
                    if (pk.hasNonNull("columnSqlType")) {
                        x.append(" sql-type=\"").append(pk.path("columnSqlType").asText()).append("\"");
                    }
                    x.append("/>\n");
                } else {
                    x.append("    <id name=\"").append(pk.path("name").asText())
                     .append("\" column=\"").append(pk.path("columnName").asText(pk.path("name").asText()))
                     .append("\" type=\"").append(hibernateType(pk)).append("\">\n");
                }
                x.append("      <generator class=\"").append(pk.path("generator").asText("assigned"))
                 .append("\"/>\n")
                 .append("    </id>\n");
            }

            List<String> fkColumns = new ArrayList<>();
            for (JsonNode r : entity.path("relationships")) {
                if ("many-to-one".equals(r.path("type").asText())) {
                    for (JsonNode j : r.path("joinColumns")) {
                        fkColumns.add(j.path("name").asText());
                    }
                }
            }

            for (JsonNode p : entity.path("properties")) {
                boolean readOnlyDuplicate = fkColumns.contains(p.path("columnName").asText());
                boolean explicitColumn = p.hasNonNull("length") || p.hasNonNull("columnSqlType");
                x.append("    <property name=\"").append(p.path("name").asText())
                 .append("\" type=\"").append(hibernateType(p))
                 .append("\" not-null=\"").append(!p.path("nullable").asBoolean(true)).append("\"");
                if (readOnlyDuplicate) {
                    x.append(" insert=\"false\" update=\"false\"");
                }
                if (explicitColumn) {
                    x.append(">\n      <column name=\"").append(p.path("columnName").asText()).append("\"");
                    if (p.hasNonNull("length")) {
                        x.append(" length=\"").append(p.path("length").asInt()).append("\"");
                    }
                    if (p.hasNonNull("columnSqlType")) {
                        x.append(" sql-type=\"").append(p.path("columnSqlType").asText()).append("\"");
                    }
                    x.append("/>\n    </property>\n");
                } else {
                    x.append(" column=\"").append(p.path("columnName").asText()).append("\"/>\n");
                }
            }

            for (JsonNode r : entity.path("relationships")) {
                String kind = r.path("type").asText();
                if ("many-to-one".equals(kind)) {
                    JsonNode join = r.path("joinColumns").path(0);
                    x.append("    <many-to-one name=\"").append(r.path("name").asText())
                     .append("\" entity-name=\"").append(r.path("targetEntity").asText())
                     .append("\" column=\"").append(join.path("name").asText())
                     .append("\"/>\n");
                } else if ("one-to-many".equals(kind)) {
                    x.append("    <set name=\"").append(r.path("name").asText())
                     .append("\" inverse=\"true\" lazy=\"true\">\n")
                     .append("      <key column=\"").append(r.path("foreignKeyColumn").asText())
                     .append("\"/>\n")
                     .append("      <one-to-many entity-name=\"").append(r.path("targetEntity").asText())
                     .append("\"/>\n")
                     .append("    </set>\n");
                }
            }

            x.append("  </class>\n</hibernate-mapping>\n");
            return x.toString();
        }

        static String hibernateType(JsonNode field) {
            String javaType = field.path("type").asText();
            switch (javaType) {
                case "java.lang.String": return "string";
                case "java.lang.Long": return "long";
                case "java.lang.Integer": return "integer";
                case "java.lang.Short": return "short";
                case "java.lang.Boolean": return "boolean";
                case "java.lang.Float": return "float";
                case "java.lang.Double": return "double";
                case "java.math.BigDecimal": return "big_decimal";
                case "java.util.Date": return "timestamp";
                case "byte[]": return "binary";
                default: return javaType;
            }
        }

        private static String esc(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }
}
