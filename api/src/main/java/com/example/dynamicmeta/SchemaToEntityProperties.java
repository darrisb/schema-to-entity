package com.example.dynamicmeta;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.dynamicmeta.connection.SecretRedactor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("schema-to-entity")
public class SchemaToEntityProperties {

    private boolean enabled = true;
    private boolean webEnabled = true;
    /**
     * Off by default: explicit connections let callers submit database addresses and
     * credentials. Host applications must opt in, and production should keep this off
     * or put the endpoint behind authentication and authorization.
     */
    private boolean explicitConnectionsEnabled = false;
    private String schema;
    private List<String> allowedHosts = List.of();
    private boolean allowEmbeddedDatabases = false;
    /** Include database views and materialized views alongside tables. */
    private boolean includeViews = true;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration socketTimeout = Duration.ofSeconds(60);
    private int maxConcurrentExplicitConnections = 4;

    /**
     * Path of the JSON overlay file that keeps user edits — labels,
     * descriptions and relationship decisions — so rediscovery never wipes
     * them. The only file this starter writes itself; security settings such
     * as hidden columns stay in configuration, not in the overlay.
     */
    private String overlayFile = "./schema-overlay.json";

    /**
     * Folder holding the accepted baseline {@link
     * com.example.dynamicmeta.drift.SchemaSnapshot} of each source as
     * {@code <source>.json}, with superseded baselines under
     * {@code history/<source>/}. Drift detection reads and writes these
     * files; nothing here touches a source database. The folder is created
     * when it is missing.
     */
    private String snapshotDir = "./snapshots";

    /**
     * Named read-only data sources. The data API reads from these sources by
     * name; generated UIs cannot and must not send database credentials.
     */
    private Map<String, SourceProperties> sources = new LinkedHashMap<>();

    /** Off by default: the read-only data API is opt-in. */
    private boolean dataApiEnabled = false;
    /**
     * Off by default: the graph endpoints expose the whole combined schema
     * document and accept overlay edits, so host applications must opt in
     * and should keep them behind authentication and authorization.
     */
    private boolean graphEnabled = false;
    /** Maximum number of rows a data API query may return. */
    private int maxPageSize = 200;
    /** Number of rows a data API query returns when the caller does not specify one. */
    private int defaultPageSize = 50;
    /** Maximum time a data API query may run before it is cancelled. */
    private Duration queryTimeout = Duration.ofSeconds(15);
    /** How long a discovered source schema is cached before rediscovery. */
    private Duration schemaCacheTtl = Duration.ofMinutes(10);
    /**
     * Origins allowed to call the read-only data API from a browser. Empty
     * (the default) sends no CORS headers. A literal {@code *} is the only
     * wildcard and must be written explicitly.
     */
    private List<String> dataAllowedOrigins = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public void setWebEnabled(boolean webEnabled) {
        this.webEnabled = webEnabled;
    }

    public boolean isExplicitConnectionsEnabled() {
        return explicitConnectionsEnabled;
    }

    public void setExplicitConnectionsEnabled(boolean explicitConnectionsEnabled) {
        this.explicitConnectionsEnabled = explicitConnectionsEnabled;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public boolean isAllowEmbeddedDatabases() {
        return allowEmbeddedDatabases;
    }

    public void setAllowEmbeddedDatabases(boolean allowEmbeddedDatabases) {
        this.allowEmbeddedDatabases = allowEmbeddedDatabases;
    }

    public boolean isIncludeViews() {
        return includeViews;
    }

    public void setIncludeViews(boolean includeViews) {
        this.includeViews = includeViews;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(Duration socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public int getMaxConcurrentExplicitConnections() {
        return maxConcurrentExplicitConnections;
    }

    public void setMaxConcurrentExplicitConnections(int maxConcurrentExplicitConnections) {
        this.maxConcurrentExplicitConnections = maxConcurrentExplicitConnections;
    }

    public String getOverlayFile() {
        return overlayFile;
    }

    public void setOverlayFile(String overlayFile) {
        this.overlayFile = overlayFile;
    }

    public String getSnapshotDir() {
        return snapshotDir;
    }

    public void setSnapshotDir(String snapshotDir) {
        this.snapshotDir = snapshotDir;
    }

    public Map<String, SourceProperties> getSources() {
        return sources;
    }

    public void setSources(Map<String, SourceProperties> sources) {
        this.sources = sources;
    }

    public boolean isDataApiEnabled() {
        return dataApiEnabled;
    }

    public void setDataApiEnabled(boolean dataApiEnabled) {
        this.dataApiEnabled = dataApiEnabled;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public Duration getSchemaCacheTtl() {
        return schemaCacheTtl;
    }

    public void setSchemaCacheTtl(Duration schemaCacheTtl) {
        this.schemaCacheTtl = schemaCacheTtl;
    }

    public boolean isGraphEnabled() {
        return graphEnabled;
    }

    public void setGraphEnabled(boolean graphEnabled) {
        this.graphEnabled = graphEnabled;
    }

    public List<String> getDataAllowedOrigins() {
        return dataAllowedOrigins;
    }

    public void setDataAllowedOrigins(List<String> dataAllowedOrigins) {
        this.dataAllowedOrigins = dataAllowedOrigins;
    }

    /**
     * Configuration for one named read-only data source. Passwords should be
     * supplied from the environment through placeholders such as
     * {@code ${ORDERS_DB_PASSWORD}}; never commit literal passwords.
     */
    public static class SourceProperties {

        private String url;
        private String username;
        private String password;
        private String schema;
        /** Optional allowlist of tables; empty means all tables. */
        private List<String> tables = List.of();
        private Boolean ssl;
        private int maxPoolSize = 4;
        /** View name to key column, used when a view has no primary key. */
        private Map<String, String> viewKeys = Map.of();

        /**
         * Columns never returned, filtered or listed by the data API. An entry is
         * either a bare column name (hidden in every table and view of the source)
         * or {@code entity.column}. Matching ignores case.
         */
        private List<String> hiddenColumns = List.of();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public List<String> getTables() {
            return tables;
        }

        public void setTables(List<String> tables) {
            this.tables = tables;
        }

        public Boolean getSsl() {
            return ssl;
        }

        public void setSsl(Boolean ssl) {
            this.ssl = ssl;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public Map<String, String> getViewKeys() {
            return viewKeys;
        }

        public void setViewKeys(Map<String, String> viewKeys) {
            this.viewKeys = viewKeys;
        }

        public List<String> getHiddenColumns() {
            return hiddenColumns;
        }

        public void setHiddenColumns(List<String> hiddenColumns) {
            this.hiddenColumns = hiddenColumns;
        }

        /**
         * Never prints the password. The URL is passed through
         * {@link SecretRedactor} because URI-form URLs may carry credentials.
         */
        @Override
        public String toString() {
            return "SourceProperties[" +
                    "url=" + (url == null ? "null" : SecretRedactor.redact(url)) +
                    ", username=" + username +
                    ", password=" + (password == null ? "null" : "***") +
                    ", schema=" + schema +
                    ", tables=" + tables +
                    ", ssl=" + ssl +
                    ", maxPoolSize=" + maxPoolSize +
                    ", viewKeys=" + viewKeys +
                    ", hiddenColumns=" + hiddenColumns + "]";
        }
    }
}
