package com.example.dynamicmeta.data;

/**
 * Thrown when a caller asks for a named data source that is not configured.
 * <p>
 * The message contains only the requested name; it never includes a URL,
 * credentials or other connection details.
 */
public class SourceNotFoundException extends RuntimeException {

    public SourceNotFoundException(String name) {
        super("Unknown data source: " + name);
    }
}
