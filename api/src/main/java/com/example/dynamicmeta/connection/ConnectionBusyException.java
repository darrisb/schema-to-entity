package com.example.dynamicmeta.connection;

/**
 * Thrown when the connection service is busy (e.g., too many concurrent connections).
 * Maps to HTTP 429 Too Many Requests.
 */
public class ConnectionBusyException extends ConnectionException {

    public ConnectionBusyException(String message) {
        super(message);
    }

    public ConnectionBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
