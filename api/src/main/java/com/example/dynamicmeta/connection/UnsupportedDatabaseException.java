package com.example.dynamicmeta.connection;

/**
 * Thrown when the requested database type is not supported. Maps to HTTP 422 Unprocessable Entity.
 */
public class UnsupportedDatabaseException extends ConnectionException {

    public UnsupportedDatabaseException(String message) {
        super(message);
    }

    public UnsupportedDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
