package com.example.dynamicmeta.connection;

/**
 * Thrown when a connection is rejected by policy (e.g., explicit connections disabled,
 * host not allowed, forbidden URL parameter). Maps to HTTP 403 Forbidden.
 */
public class ConnectionRejectedException extends ConnectionException {

    public ConnectionRejectedException(String message) {
        super(message);
    }

    public ConnectionRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
