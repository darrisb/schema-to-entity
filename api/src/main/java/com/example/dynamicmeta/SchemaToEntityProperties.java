package com.example.dynamicmeta;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("schema-to-entity")
public class SchemaToEntityProperties {

    private boolean enabled = true;
    private boolean webEnabled = true;
    private boolean explicitConnectionsEnabled = true;
    private String schema;
    private List<String> allowedHosts = List.of();
    private boolean allowEmbeddedDatabases = false;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration socketTimeout = Duration.ofSeconds(60);
    private int maxConcurrentExplicitConnections = 4;

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
}
