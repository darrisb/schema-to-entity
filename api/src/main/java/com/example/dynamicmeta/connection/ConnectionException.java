package com.example.dynamicmeta.connection;

/**
 * Base runtime exception for database connection failures.
 * Subclasses map to specific HTTP status codes via {@link com.example.dynamicmeta.SchemaDiscoveryController}.
 */
public class ConnectionException extends RuntimeException {

    public ConnectionException(String message) {
        super(message);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
