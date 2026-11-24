package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DA-003: each source's discovered schema is cached as the typed query
 * allowlist. The data API is read-only; these tests only read metadata.
 */
class SchemaCacheTests {

    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    @Test
    void discoversTableAndViewWithKindsCategoriesAndKeys() throws Exception {
        seed("ste-cache-model",
                "create table customers (id bigint primary key, name varchar(80) not null, "
                        + "born date, active boolean, created timestamp, external_id uuid)",
                "create view customer_names as select id, name from customers");
        SourceProperties source = h2Source("ste-cache-model");
        source.setViewKeys(Map.of("customer_names", "id"));
        SourceRegistry registry = registry(source);
        SchemaCache cache = new SchemaCache(registry, discovery(), new SchemaToEntityProperties());

        SourceSchema schema = cache.get("orders");

        assertThat(schema.databaseType()).isEqualTo("h2");
        assertThat(schema.schema()).isEqualTo("PUBLIC");
        assertThat(schema.entities()).containsOnlyKeys("customers", "customer_names");

        EntityModel customers = schema.entity("CUSTOMERS");
        assertThat(customers.kind()).isEqualTo("table");
        assertThat(customers.isView()).isFalse();
        assertThat(customers.keyColumns()).containsExactly("ID");
        assertThat(customers.columns()).extracting(ColumnModel::name)
                .containsExactly("ID", "NAME", "BORN", "ACTIVE", "CREATED", "EXTERNAL_ID");

        Map<String, ColumnCategory> categories = new LinkedHashMap<>();
        Map<String, Boolean> nullability = new LinkedHashMap<>();
        for (ColumnModel column : customers.columns()) {
            categories.put(column.name(), column.category());
            nullability.put(column.name(), column.nullable());
        }
        assertThat(categories)
                .containsEntry("ID", ColumnCategory.NUMBER)
                .containsEntry("NAME", ColumnCategory.TEXT)
                .containsEntry("BORN", ColumnCategory.DATE)
                .containsEntry("ACTIVE", ColumnCategory.BOOLEAN)
                .containsEntry("CREATED", ColumnCategory.TIMESTAMP)
                .containsEntry("EXTERNAL_ID", ColumnCategory.OTHER);
        assertThat(nullability).containsEntry("NAME", false);
        assertThat(nullability).containsEntry("ID", false);

        EntityModel view = schema.entity("customer_names");
        assertThat(view.kind()).isEqualTo("view");
        assertThat(view.isView()).isTrue();
        assertThat(view.keyColumns()).containsExactly("id");
        assertThat(view.columns()).extracting(ColumnModel::name).containsExactly("ID", "NAME");

        assertThatThrownBy(() -> schema.entity("missing"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> cache.entity("orders", "missing"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("missing");

        registry.destroy();
    }

    @Test
    void viewKeysSetAViewKeyAndAnEntryNamingAMissingColumnIsIgnored() throws Exception {
        seed("ste-cache-viewkeys",
                "create table customers (id bigint primary key, name varchar(80) not null)",
                "create view customer_names as select id, name from customers",
                "create view customer_summary as select id from customers");
        SourceProperties source = h2Source("ste-cache-viewkeys");
        // Upper-case view name proves the match ignores case.
        source.setViewKeys(Map.of(
                "CUSTOMER_NAMES", "ID",
                "customer_summary", "does_not_exist"));
        SourceRegistry registry = registry(source);
        SchemaCache cache = new SchemaCache(registry, discovery(), new SchemaToEntityProperties());

        SourceSchema schema = cache.get("orders");

        assertThat(schema.entity("customer_names").keyColumns()).containsExactly("ID");
        assertThat(schema.entity("customer_summary").keyColumns()).isEmpty();

        registry.destroy();
    }

    @Test
    void respectsTheConfiguredTablesAllowlist() throws Exception {
        seed("ste-cache-allowlist",
                "create table customers (id bigint primary key, name varchar(80) not null)",
                "create table orders (id bigint primary key, customer_id bigint not null)",
                "create view customer_names as select id, name from customers");
        SourceProperties source = h2Source("ste-cache-allowlist");
        source.setTables(List.of("customers"));
        SourceRegistry registry = registry(source);
        SchemaCache cache = new SchemaCache(registry, discovery(), new SchemaToEntityProperties());

        SourceSchema schema = cache.get("orders");

        assertThat(schema.entities()).containsOnlyKeys("customers");

        registry.destroy();
    }

    @Test
    void expiredTtlRediscoversAndRefreshForcesRediscovery() throws Exception {
        seed("ste-cache-ttl", "create table customers (id bigint primary key)");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SourceRegistry registry = registry(h2Source("ste-cache-ttl"));
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSchemaCacheTtl(Duration.ofMinutes(10));
        SchemaCache cache = new SchemaCache(registry, discovery(), properties, clock);

        SourceSchema first = cache.get("orders");
        assertThat(first.entities()).containsOnlyKeys("customers");

        seed("ste-cache-ttl", "create table added_later (id bigint primary key)");
        assertThat(cache.get("orders").entities()).containsOnlyKeys("customers");

        clock.advance(Duration.ofMinutes(11));
        assertThat(cache.get("orders").entities())
                .containsOnlyKeys("customers", "added_later");

        seed("ste-cache-ttl", "create table refreshed_now (id bigint primary key)");
        SourceSchema refreshed = cache.refresh("orders");
        assertThat(refreshed.entities())
                .containsOnlyKeys("customers", "added_later", "refreshed_now");
        assertThat(cache.get("orders")).isSameAs(refreshed);

        registry.destroy();
    }

    @Test
    void concurrentFirstCallsDiscoverOnlyOnce() throws Exception {
        seed("ste-cache-concurrent", "create table orders (id bigint primary key)");
        SourceRegistry registry = registry(h2Source("ste-cache-concurrent"));
        SchemaDiscoveryService discoveryService = mock(SchemaDiscoveryService.class);
        AtomicInteger discoveries = new AtomicInteger();
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        when(discoveryService.inspect(any(), any(), any())).thenAnswer(invocation -> {
            discoveries.incrementAndGet();
            discoveryStarted.countDown();
            assertThat(releaseDiscovery.await(5, TimeUnit.SECONDS)).isTrue();
            return Map.of(
                    "dialect", "h2",
                    "catalog", "STE_CACHE_CONCURRENT",
                    "schema", "PUBLIC",
                    "tables", List.of(Map.of(
                            "name", "ORDERS",
                            "kind", "table",
                            "columns", List.of(),
                            "primaryKey", "ID")));
        });
        SchemaCache cache = new SchemaCache(
                registry, discoveryService, new SchemaToEntityProperties());

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch submitted = new CountDownLatch(threadCount);
        List<Future<SourceSchema>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                submitted.countDown();
                return cache.get("orders");
            }));
        }

        assertThat(discoveryStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
        // Let every other thread reach the per-source lock before releasing
        // the single discovery in progress.
        Thread.sleep(300);
        releaseDiscovery.countDown();

        for (Future<SourceSchema> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isNotNull();
        }
        executor.shutdown();

        assertThat(discoveries).hasValue(1);
        registry.destroy();
    }

    @Test
    void unknownSourceThrowsSourceNotFoundException() {
        SourceRegistry registry = registry(h2Source("ste-cache-unknown"));
        SchemaCache cache = new SchemaCache(registry, discovery(), new SchemaToEntityProperties());

        assertThatThrownBy(() -> cache.get("missing"))
                .isInstanceOf(SourceNotFoundException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> cache.refresh("missing"))
                .isInstanceOf(SourceNotFoundException.class)
                .hasMessageContaining("missing");

        registry.destroy();
    }

    @Test
    void schemaCacheTtlDefaultsToTenMinutes() {
        assertThat(new SchemaToEntityProperties().getSchemaCacheTtl())
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void schemaCacheIsRegisteredWithTheDataApi() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, SchemaCacheTests::hostDataSource)
                .withPropertyValues(
                        "schema-to-entity.data-api-enabled=true",
                        "schema-to-entity.allow-embedded-databases=true",
                        "schema-to-entity.sources.orders.url="
                                + "jdbc:h2:mem:ste_cache_auto_orders;DB_CLOSE_DELAY=-1",
                        "schema-to-entity.sources.orders.username=sa");

        runner.run(context -> {
            assertThat(context).hasSingleBean(SchemaCache.class);
            assertThat(context).hasSingleBean(SourceRegistry.class);
        });

        runner.withPropertyValues("schema-to-entity.data-api-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SchemaCache.class));
    }

    @Test
    void hiddenColumnsAreLeftOutOfTheModel() throws Exception {
        seed("ste-cache-hidden",
                "create table users (id bigint primary key, email varchar(120), "
                        + "password_hash varchar(200))",
                "create view user_emails as select id, email from users");
        SourceProperties source = h2Source("ste-cache-hidden");
        source.setViewKeys(Map.of("user_emails", "id"));
        // Bare name hides the column everywhere; entity.column hides it in one entity.
        source.setHiddenColumns(List.of("PASSWORD_HASH", "users.email"));
        SchemaCache cache = new SchemaCache(registry(source), discovery(),
                new SchemaToEntityProperties());

        SourceSchema schema = cache.get("orders");

        assertThat(schema.entity("users").columns()).extracting(ColumnModel::name)
                .containsExactly("ID");
        assertThat(schema.entity("users").keyColumns()).containsExactly("ID");
        assertThat(schema.entity("user_emails").columns()).extracting(ColumnModel::name)
                .containsExactly("ID", "EMAIL");
    }

    @Test
    void hidingTheKeyColumnRemovesTheKey() throws Exception {
        seed("ste-cache-hidden-key",
                "create table tokens (id bigint primary key, label varchar(40))");
        SourceProperties source = h2Source("ste-cache-hidden-key");
        source.setHiddenColumns(List.of("id"));
        SchemaCache cache = new SchemaCache(registry(source), discovery(),
                new SchemaToEntityProperties());

        EntityModel tokens = cache.get("orders").entity("tokens");

        assertThat(tokens.columns()).extracting(ColumnModel::name).containsExactly("LABEL");
        assertThat(tokens.keyColumns()).isEmpty();
    }

    @Test
    void unknownSourceIsRejectedWithoutBeingCached() throws Exception {
        SchemaCache cache = new SchemaCache(registry(h2Source("ste-cache-unknown-names")),
                discovery(), new SchemaToEntityProperties());

        for (int i = 0; i < 3; i++) {
            String name = "random-" + i;
            assertThatThrownBy(() -> cache.get(name))
                    .isInstanceOf(SourceNotFoundException.class);
            assertThatThrownBy(() -> cache.refresh(name))
                    .isInstanceOf(SourceNotFoundException.class);
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static DataSource hostDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ste_cache_auto_host;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static SourceProperties h2Source(String database) {
        SourceProperties source = new SourceProperties();
        source.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        return source;
    }

    private static Map<String, DataSourceDefinition> definitions(SourceProperties source) {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));
        return DataSourceDefinitions.from(properties, PARSER, POLICY);
    }

    private static SourceRegistry registry(SourceProperties source) {
        return new SourceRegistry(definitions(source), FACTORY, PARSER, Duration.ofSeconds(10));
    }

    private static SchemaDiscoveryService discovery() {
        return new SchemaDiscoveryService(null, new SchemaToEntityProperties(), POLICY, FACTORY);
    }

    private static void seed(String database, String... statements) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Clock whose time tests can advance without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
