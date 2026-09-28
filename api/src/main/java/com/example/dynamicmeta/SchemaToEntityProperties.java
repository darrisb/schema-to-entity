package com.example.dynamicmeta;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("schema-to-entity")
public class SchemaToEntityProperties {

    private boolean enabled = true;
    private boolean webEnabled = true;
    private boolean explicitConnectionsEnabled = true;
    private String schema;

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
}
