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

import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.data.DataController;
import com.example.dynamicmeta.data.DataCorsConfiguration;
import com.example.dynamicmeta.data.DataQueryService;
import com.example.dynamicmeta.data.DataSourceDefinitions;
import com.example.dynamicmeta.data.QueryParameterParser;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceDefinitions;
import com.example.dynamicmeta.data.SourceRegistry;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "schema-to-entity", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SchemaToEntityProperties.class)
public class SchemaToEntityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ConnectionPolicy connectionPolicy(SchemaToEntityProperties properties) {
        return new ConnectionPolicy(
                properties.getAllowedHosts(),
                properties.isAllowEmbeddedDatabases());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcConnectionFactory jdbcConnectionFactory(SchemaToEntityProperties properties) {
        return new JdbcConnectionFactory(
                properties.getConnectTimeout(),
                properties.getSocketTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    ConnectionStringParser connectionStringParser() {
        return new ConnectionStringParser();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled")
    SourceDefinitions dataSourceDefinitions(
            SchemaToEntityProperties properties,
            ConnectionStringParser parser,
            ConnectionPolicy policy) {
        return new SourceDefinitions(DataSourceDefinitions.from(properties, parser, policy));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled")
    SourceRegistry sourceRegistry(
            SourceDefinitions definitions,
            JdbcConnectionFactory jdbcConnectionFactory,
            ConnectionStringParser parser,
            SchemaToEntityProperties properties) {
        return new SourceRegistry(definitions.definitions(), jdbcConnectionFactory, parser,
                properties.getConnectTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled")
    SchemaCache schemaCache(
            SourceRegistry sourceRegistry,
            SchemaDiscoveryService schemaDiscoveryService,
            SchemaToEntityProperties properties) {
        return new SchemaCache(sourceRegistry, schemaDiscoveryService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled")
    QueryParameterParser queryParameterParser(SchemaToEntityProperties properties) {
        return new QueryParameterParser(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled")
    DataQueryService dataQueryService(
            SourceRegistry sourceRegistry,
            SchemaCache schemaCache,
            QueryParameterParser queryParameterParser,
            SchemaToEntityProperties properties) {
        return new DataQueryService(
                sourceRegistry, schemaCache, queryParameterParser, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    SchemaDiscoveryService schemaDiscoveryService(
            DataSource dataSource,
            SchemaToEntityProperties properties,
            ConnectionPolicy policy,
            JdbcConnectionFactory jdbcConnectionFactory) {
        return new SchemaDiscoveryService(dataSource, properties, policy, jdbcConnectionFactory);
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

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled",
                havingValue = "true")
        DataController dataController(DataQueryService queryService) {
            return new DataController(queryService);
        }

        /**
         * DA-007: browser access to the read-only data endpoints only. The
         * mapping is path-scoped, so admin endpoints such as
         * {@code /api/schema/extract} are unaffected.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "schema-to-entity", name = "data-api-enabled",
                havingValue = "true")
        DataCorsConfiguration dataCorsConfiguration(SchemaToEntityProperties properties) {
            return new DataCorsConfiguration(properties);
        }
    }
}
