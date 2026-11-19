package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DA-001: named data sources are validated configuration only; nothing connects.
 */
class DataSourceDefinitionsTests {

    private static final String PASSWORD = "s3cr3t-p@ss";

    private static SourceProperties h2Source() {
        SourceProperties source = new SourceProperties();
        source.setUrl("jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword(PASSWORD);
        source.setSchema("PUBLIC");
        source.setTables(List.of("ORDERS", "ORDER_ITEMS"));
        source.setSsl(true);
        source.setViewKeys(Map.of("ORDER_SUMMARY", "ORDER_ID"));
        return source;
    }

    private static SchemaToEntityProperties properties(SourceProperties source) {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));
        return properties;
    }

    private static Map<String, DataSourceDefinition> build(
            SourceProperties source, ConnectionPolicy policy) {
        return DataSourceDefinitions.from(
                properties(source), new ConnectionStringParser(), policy);
    }

    private static IllegalStateException invalidSource(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException ex) {
            return ex;
        }
        throw new AssertionError("Expected IllegalStateException but nothing was thrown");
    }

    @Test
    void propertiesHaveExpectedDefaults() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();

        assertThat(properties.getSources()).isEmpty();
        assertThat(properties.isDataApiEnabled()).isFalse();
        assertThat(properties.getMaxPageSize()).isEqualTo(200);
        assertThat(properties.getDefaultPageSize()).isEqualTo(50);
        assertThat(properties.getQueryTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void validEmbeddedH2SourceProducesDefinition() {
        Map<String, DataSourceDefinition> definitions = build(
                h2Source(), new ConnectionPolicy(List.of(), true));

        assertThat(definitions).containsOnlyKeys("orders");
        DataSourceDefinition definition = definitions.get("orders");
        assertThat(definition.name()).isEqualTo("orders");
        assertThat(definition.url()).isEqualTo("jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1");
        assertThat(definition.username()).isEqualTo("sa");
        assertThat(definition.password()).isEqualTo(PASSWORD);
        assertThat(definition.schema()).isEqualTo("PUBLIC");
        assertThat(definition.tables()).containsExactly("ORDERS", "ORDER_ITEMS");
        assertThat(definition.ssl()).isTrue();
        assertThat(definition.maxPoolSize()).isEqualTo(4);
        assertThat(definition.viewKeys()).containsEntry("ORDER_SUMMARY", "ORDER_ID");
    }

    @Test
    void emptySourcesProduceEmptyImmutableMap() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        Map<String, DataSourceDefinition> definitions = DataSourceDefinitions.from(
                properties, new ConnectionStringParser(), new ConnectionPolicy(List.of(), false));

        assertThat(definitions).isEmpty();
        assertThatThrownBy(() -> definitions.put("orders", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Orders", "bad name", "orders_db", ""})
    void badNameFailsNamingTheSource(String name) {
        SourceProperties source = h2Source();
        source.setPassword(PASSWORD);
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of(name, source));

        IllegalStateException ex = invalidSource(() -> DataSourceDefinitions.from(
                properties, new ConnectionStringParser(), new ConnectionPolicy(List.of(), true)));

        assertThat(ex.getMessage()).contains(name).contains("name must match");
        assertThat(ex.getMessage()).doesNotContain(PASSWORD);
    }

    @Test
    void badUrlFailsNamingTheSource() {
        SourceProperties source = h2Source();
        source.setUrl("jdbc:postgresql://");

        IllegalStateException ex = invalidSource(() -> build(
                source, new ConnectionPolicy(List.of(), false)));

        assertThat(ex.getMessage()).contains("orders").contains("host is required");
        assertThat(ex.getMessage()).doesNotContain(PASSWORD);
    }

    @Test
    void forbiddenParameterFailsNamingTheSource() {
        SourceProperties source = h2Source();
        source.setUrl("jdbc:postgresql://host:5432/db"
                + "?user=sa&password=" + PASSWORD + "&socketFactory=com.evil.Factory");

        IllegalStateException ex = invalidSource(() -> build(
                source, new ConnectionPolicy(List.of(), false)));

        assertThat(ex.getMessage()).contains("orders").contains("socketFactory");
        assertThat(ex.getMessage()).doesNotContain(PASSWORD).doesNotContain("jdbc:");
        assertThat(ex.getMessage()).doesNotContain("com.evil");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void poolSizeOutsideOneToTwentyFailsNamingTheSource(int maxPoolSize) {
        SourceProperties source = h2Source();
        source.setMaxPoolSize(maxPoolSize);

        IllegalStateException ex = invalidSource(() -> build(
                source, new ConnectionPolicy(List.of(), true)));

        assertThat(ex.getMessage()).contains("orders").contains("maxPoolSize");
        assertThat(ex.getMessage()).doesNotContain(PASSWORD);
    }

    @Test
    void urlCredentialsAreSeparatedFromTheUrl() {
        SourceProperties source = h2Source();
        source.setUrl("jdbc:postgresql://host:5432/db?user=dbuser&password=" + PASSWORD);
        source.setUsername(null);
        source.setPassword(null);

        DataSourceDefinition definition = build(
                source, new ConnectionPolicy(List.of(), false)).get("orders");

        assertThat(definition.url()).isEqualTo("jdbc:postgresql://host:5432/db");
        assertThat(definition.username()).isEqualTo("dbuser");
        assertThat(definition.password()).isEqualTo(PASSWORD);
    }

    @Test
    void sourcePropertiesToStringNeverPrintsThePassword() {
        SourceProperties source = h2Source();
        source.setUrl("jdbc:postgresql://host:5432/db?user=sa&password=" + PASSWORD);

        String text = source.toString();

        assertThat(text).doesNotContain(PASSWORD).contains("password=***");
    }

    @Test
    void definitionToStringNeverPrintsThePassword() {
        SourceProperties source = h2Source();
        source.setUrl("jdbc:postgresql://host:5432/db?user=sa&password=" + PASSWORD);

        DataSourceDefinition definition = build(
                source, new ConnectionPolicy(List.of(), false)).get("orders");
        String text = definition.toString();

        assertThat(text).doesNotContain(PASSWORD).contains("password=***");
    }

    @Test
    void exceptionMessagesNeverPrintTheUrlOrPassword() {
        SourceProperties source = h2Source();
        source.setUrl("jdbc:postgresql://host:5432/db?user=sa&password=" + PASSWORD
                + "&socketFactory=com.evil.Factory");

        IllegalStateException ex = invalidSource(() -> build(
                source, new ConnectionPolicy(List.of(), false)));

        assertThat(ex.getMessage())
                .doesNotContain(PASSWORD)
                .doesNotContain("jdbc:")
                .doesNotContain("host:5432");
    }
}
