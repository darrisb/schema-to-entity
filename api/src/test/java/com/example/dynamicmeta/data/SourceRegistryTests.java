package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DA-002: one lazy, read-only HikariCP pool per configured source.
 */
class SourceRegistryTests {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(CONNECT_TIMEOUT, Duration.ofSeconds(60));

    private static SourceProperties h2Source(String database) {
        SourceProperties source = new SourceProperties();
        source.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        source.setMaxPoolSize(3);
        return source;
    }

    private static Map<String, DataSourceDefinition> definitions(SourceProperties source) {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));
        return DataSourceDefinitions.from(
                properties, PARSER, new ConnectionPolicy(List.of(), true));
    }

    private static SourceRegistry registry(SourceProperties source) {
        return registry(source, CONNECT_TIMEOUT);
    }

    private static SourceRegistry registry(SourceProperties source, Duration connectTimeout) {
        return new SourceRegistry(definitions(source), FACTORY, PARSER, connectTimeout);
    }

    private static void seedOrdersTable(String jdbcUrl) throws Exception {
        JdbcDataSource seed = new JdbcDataSource();
        seed.setURL(jdbcUrl);
        seed.setUser("sa");
        seed.setPassword("");
        try (Connection connection = seed.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table orders (id bigint primary key, name varchar(80) not null)");
            statement.execute("insert into orders values (1, 'first')");
        }
    }

    @Test
    void h2SourceReturnsWorkingReadOnlyConnection() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:ste-registry-orders;DB_CLOSE_DELAY=-1";
        seedOrdersTable(jdbcUrl);
        SourceRegistry registry = registry(h2Source("ste-registry-orders"));

        DataSource dataSource = registry.get("orders");

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource pool = (HikariDataSource) dataSource;
        assertThat(pool.getJdbcUrl()).isEqualTo(jdbcUrl);
        assertThat(pool.getPoolName()).isEqualTo("ste-orders");
        assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
        assertThat(pool.getConnectionTimeout()).isEqualTo(CONNECT_TIMEOUT.toMillis());
        assertThat(pool.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(pool.isReadOnly()).isTrue();

        try (Connection connection = dataSource.getConnection()) {
            // Hikari applies the pool's read-only flag through
            // Connection.setReadOnly(true); H2's driver accepts the call but
            // does not report it back through isReadOnly(), so the pool flag
            // above is the observable contract. The connection must still work.
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "select name from orders where id = 1")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("first");
            }
        }

        assertThat(registry.names()).containsExactly("orders");
        registry.destroy();
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void unknownSourceThrowsSourceNotFoundException() {
        SourceRegistry registry = registry(h2Source("ste-registry-unknown"));

        assertThat(registry.names()).containsExactly("orders");
        assertThatThrownBy(() -> registry.get("customers"))
                .isInstanceOf(SourceNotFoundException.class)
                .hasMessageContaining("customers");

        registry.destroy();
    }

    @Test
    void poolIsCreatedOnceAndClosedOnDestroy() {
        SourceRegistry registry = registry(h2Source("ste-registry-once"));

        DataSource first = registry.get("orders");
        DataSource second = registry.get("orders");

        assertThat(second).isSameAs(first);
        assertThat(((HikariDataSource) first).isClosed()).isFalse();

        registry.destroy();

        assertThat(((HikariDataSource) first).isClosed()).isTrue();
    }

    @Test
    void namesListsEveryConfiguredSource() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(new LinkedHashMap<>(Map.of(
                "orders", h2Source("ste-registry-multi-orders"),
                "customers", h2Source("ste-registry-multi-customers"))));
        Map<String, DataSourceDefinition> definitions = DataSourceDefinitions.from(
                properties, PARSER, new ConnectionPolicy(List.of(), true));
        SourceRegistry registry =
                new SourceRegistry(definitions, FACTORY, PARSER, CONNECT_TIMEOUT);

        assertThat(registry.names())
                .containsExactlyInAnyOrder("orders", "customers");

        registry.destroy();
    }

    @Test
    void downSourceDoesNotFailStartupAndPoolIsCreatedLazily() {
        SourceProperties source = new SourceProperties();
        // Nothing listens on port 1, but the URL itself is valid.
        source.setUrl("jdbc:postgresql://127.0.0.1:1/ste_down");
        source.setUsername("reader");
        source.setPassword("reader");
        SourceRegistry registry = registry(source, Duration.ofMillis(250));

        // Construction is lazy: a down database must not fail startup.
        assertThat(registry.names()).containsExactly("orders");

        DataSource dataSource = registry.get("orders");
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThatThrownBy(() -> dataSource.getConnection())
                .isInstanceOf(SQLException.class);

        registry.destroy();
    }
}
