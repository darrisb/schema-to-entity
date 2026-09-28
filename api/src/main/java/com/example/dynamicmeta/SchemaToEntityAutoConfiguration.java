package com.example.dynamicmeta;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "schema-to-entity", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SchemaToEntityProperties.class)
public class SchemaToEntityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SchemaDiscoveryService schemaDiscoveryService(
            DataSource dataSource, SchemaToEntityProperties properties) {
        return new SchemaDiscoveryService(dataSource, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    SpringMetadataTransformer springMetadataTransformer() {
        return new SpringMetadataTransformer();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "web-enabled", matchIfMissing = true)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SchemaDiscoveryController schemaDiscoveryController(
                SchemaDiscoveryService service, SpringMetadataTransformer transformer) {
            return new SchemaDiscoveryController(service, transformer);
        }
    }
}
