package com.example.dynamicmeta.data;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.connection.ParsedConnection;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.DisposableBean;

/**
 * Owns one small, read-only HikariCP pool per configured source.
 * <p>
 * Pools are created lazily on the first {@link #get(String)} call, so simply
 * configuring a source never opens a connection and a source database that is
 * down cannot fail application startup. Each pool uses the source's canonical
 * JDBC URL and the same timeout, SSL and read-only properties as one-off
 * explicit connections, via {@link JdbcConnectionFactory#buildProperties}.
 * <p>
 * The registry is bound to the application lifecycle through
 * {@link DisposableBean}; {@link #destroy()} closes every pool that was
 * created. The data API is read-only: no pool is ever used to issue
 * INSERT, UPDATE, DELETE or DDL.
 */
public class SourceRegistry implements DisposableBean {

    private final Map<String, DataSourceDefinition> definitions;
    private final JdbcConnectionFactory connectionFactory;
    private final ConnectionStringParser parser;
    private final Duration connectTimeout;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    /**
     * @param definitions      validated, immutable source definitions keyed by name
     * @param connectionFactory builds the per-database driver properties
     * @param parser           parses each source URL into its canonical form
     * @param connectTimeout   HikariCP connection timeout, normally
     *                         {@code schema-to-entity.connect-timeout}
     */
    public SourceRegistry(Map<String, DataSourceDefinition> definitions,
                          JdbcConnectionFactory connectionFactory,
                          ConnectionStringParser parser,
                          Duration connectTimeout) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.connectionFactory = connectionFactory;
        this.parser = parser;
        this.connectTimeout = connectTimeout;
    }

    /**
     * Returns the pool for the named source, creating it on first use.
     *
     * @throws SourceNotFoundException when no source with that name is configured
     */
    public DataSource get(String name) {
        DataSourceDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new SourceNotFoundException(name);
        }

        HikariDataSource pool = pools.get(name);
        if (pool != null) {
            return pool;
        }
        return pools.computeIfAbsent(name, key -> createPool(definition));
    }

    /** Returns the configured source names. */
    public Set<String> names() {
        return definitions.keySet();
    }

    @Override
    public void destroy() {
        for (HikariDataSource pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }

    private HikariDataSource createPool(DataSourceDefinition definition) {
        ParsedConnection parsed = parser.parse(definition.url());

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(parsed.jdbcUrl());
        pool.setDataSourceProperties(connectionFactory.buildProperties(
                parsed, definition.username(), definition.password(), definition.ssl()));
        pool.setReadOnly(true);
        pool.setMaximumPoolSize(definition.maxPoolSize());
        pool.setConnectionTimeout(connectTimeout.toMillis());
        pool.setPoolName("ste-" + definition.name());
        // A configured source may be down when the application starts. The
        // pool is created lazily, and this keeps its initialization from
        // failing startup; the first getConnection() surfaces the error.
        pool.setInitializationFailTimeout(-1);
        return pool;
    }
}
