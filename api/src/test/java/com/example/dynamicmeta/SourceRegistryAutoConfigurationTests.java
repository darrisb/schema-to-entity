package com.example.dynamicmeta;

import com.example.dynamicmeta.data.SourceRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DA-002: the source registry is part of the read-only data API and is only
 * registered when {@code schema-to-entity.data-api-enabled} is true.
 */
class SourceRegistryAutoConfigurationTests {

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:registry_auto_config_host;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, SourceRegistryAutoConfigurationTests::dataSource);
    }

    @Test
    void registryIsAbsentWhenDataApiIsDisabled() {
        runner().run(context ->
                assertThat(context).doesNotHaveBean(SourceRegistry.class));
    }

    @Test
    void registryIsPresentWhenDataApiIsEnabled() {
        runner()
                .withPropertyValues(
                        "schema-to-entity.data-api-enabled=true",
                        "schema-to-entity.allow-embedded-databases=true",
                        "schema-to-entity.sources.orders.url="
                                + "jdbc:h2:mem:registry_auto_config_orders;DB_CLOSE_DELAY=-1",
                        "schema-to-entity.sources.orders.username=sa",
                        "schema-to-entity.sources.orders.max-pool-size=2")
                .run(context -> {
                    assertThat(context).hasSingleBean(SourceRegistry.class);
                    SourceRegistry registry = context.getBean(SourceRegistry.class);
                    assertThat(registry.names()).containsExactly("orders");
                });
    }
}
