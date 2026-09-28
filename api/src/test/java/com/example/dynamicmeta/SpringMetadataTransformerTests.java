package com.example.dynamicmeta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringMetadataTransformerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SpringMetadataTransformer transformer = new SpringMetadataTransformer();

    @Test
    void buildsEntitiesPropertiesAndRelationshipsFromTheSampleSchema() {
        Map<String, Object> metadata = transformer.transform(sampleSchema(), "Dynamic");

        assertThat(metadata).containsEntry("schemaVersion", "1.0").containsEntry("dialect", "postgresql");
        assertThat(entities(metadata)).extracting(entity -> entity.get("entityName"))
                .containsExactly("DynamicPostTag", "DynamicPost", "DynamicSession", "DynamicUser");
    }

    @Test
    void mapsColumnTypesLengthsAndNullability() {
        Map<String, Object> user = entity(transformer.transform(sampleSchema(), "Dynamic"), "DynamicUser");

        assertThat(user.get("primaryKey")).isEqualTo(Map.of(
                "name", "id", "columnName", "id", "type", "java.lang.Long", "generator", "identity"));
        assertThat(properties(user)).containsExactly(
                Map.of("name", "username", "columnName", "username", "type", "java.lang.String",
                        "nullable", false, "length", 255),
                Map.of("name", "email", "columnName", "email", "type", "java.lang.String",
                        "nullable", false, "length", 320),
                Map.of("name", "isActive", "columnName", "is_active", "type", "java.lang.Boolean",
                        "nullable", false),
                Map.of("name", "balance", "columnName", "balance", "type", "java.math.BigDecimal",
                        "nullable", true, "precision", 12, "scale", 2),
                Map.of("name", "bio", "columnName", "bio", "type", "java.lang.String",
                        "nullable", true, "columnSqlType", "text"),
                Map.of("name", "createdAt", "columnName", "created_at", "type", "java.util.Date",
                        "nullable", true));
    }

    @Test
    void emitsAnAssignedStringPrimaryKeyForFixedLengthColumns() {
        Map<String, Object> session = entity(transformer.transform(sampleSchema(), "Dynamic"), "DynamicSession");

        assertThat(session.get("primaryKey")).isEqualTo(Map.of(
                "name", "tokenHash", "columnName", "token_hash", "type", "java.lang.String",
                "generator", "assigned", "length", 64, "columnSqlType", "bpchar"));
    }

    @Test
    void emitsACompositePrimaryKeyWithNoProperties() {
        Map<String, Object> postTag = entity(transformer.transform(sampleSchema(), "Dynamic"), "DynamicPostTag");

        assertThat(postTag.get("primaryKey")).isEqualTo(Map.of(
                "name", "postIdTag", "composite", true, "generator", "assigned",
                "columns", List.of(
                        Map.of("name", "postId", "columnName", "post_id", "type", "java.lang.Long",
                                "nullable", false),
                        Map.of("name", "tag", "columnName", "tag", "type", "java.lang.String",
                                "nullable", false))));
        assertThat(properties(postTag)).isEmpty();
    }

    @Test
    void pairsEachForeignKeyWithAnInverseCollection() {
        Map<String, Object> metadata = transformer.transform(sampleSchema(), "Dynamic");

        assertThat(relationships(entity(metadata, "DynamicPost"))).containsExactly(map(
                "name", "author", "type", "many-to-one", "targetEntity", "DynamicUser", "mappedBy", null,
                "joinColumns", List.of(map("name", "author_id", "referencedColumnName", "id"))));
        assertThat(relationships(entity(metadata, "DynamicUser"))).containsExactly(
                map("name", "posts", "type", "one-to-many", "targetEntity", "DynamicPost",
                        "mappedBy", "author", "foreignKeyColumn", "author_id", "joinColumns", List.of()),
                map("name", "sessions", "type", "one-to-many", "targetEntity", "DynamicSession",
                        "mappedBy", "user", "foreignKeyColumn", "user_id", "joinColumns", List.of()));
    }

    @Test
    void skipsForeignKeysThatPointAtUnknownTables() {
        Map<String, Object> schema = Map.of("tables", List.of(
                Map.of("name", "orders", "primaryKey", "id",
                        "columns", List.of(Map.of("name", "id", "type", "int8", "primaryKey", true)),
                        "foreignKeys", List.of(Map.of("column", "customer_id",
                                "referencedTable", "missing", "referencedColumn", "id")))));

        assertThat(relationships(entity(transformer.transform(schema, "Dynamic"), "DynamicOrder"))).isEmpty();
    }

    @Test
    void appliesTheRequestedEntityNamePrefixAndFallsBackToADefault() {
        Map<String, Object> schema = Map.of("tables", List.of(
                Map.of("name", "users", "primaryKey", "id",
                        "columns", List.of(Map.of("name", "id", "type", "int8", "primaryKey", true)))));

        assertThat(entities(transformer.transform(schema, "Order"))).extracting(entity -> entity.get("entityName"))
                .containsExactly("OrderUser");
        assertThat(entities(transformer.transform(schema, null))).extracting(entity -> entity.get("entityName"))
                .containsExactly("DynamicUser");
    }

    @Test
    void producesAnEmptyEntityListForASchemaWithoutTables() {
        assertThat(entities(transformer.transform(Map.of("dialect", "postgresql"), "Dynamic"))).isEmpty();
    }

    private static Map<String, Object> sampleSchema() {
        try (InputStream stream = SchemaDiscoveryServiceTests.class.getResourceAsStream("/sample-schema.json")) {
            assertThat(stream).as("sample-schema.json on the classpath").isNotNull();
            return MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entities(Map<String, Object> metadata) {
        return (List<Map<String, Object>>) metadata.get("entities");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> properties(Map<String, Object> entity) {
        return (List<Map<String, Object>>) entity.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> relationships(Map<String, Object> entity) {
        return (List<Map<String, Object>>) entity.get("relationships");
    }

    private static Map<String, Object> entity(Map<String, Object> metadata, String entityName) {
        return entities(metadata).stream()
                .filter(candidate -> entityName.equals(candidate.get("entityName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entity named " + entityName));
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }
}
