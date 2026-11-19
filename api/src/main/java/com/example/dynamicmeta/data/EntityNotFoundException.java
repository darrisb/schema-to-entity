package com.example.dynamicmeta.data;

/**
 * Thrown when a caller looks up an entity that was not discovered for a
 * source.
 * <p>
 * The message contains only the requested entity name; it never includes a
 * URL, credentials or other connection details.
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String name) {
        super("Unknown entity: " + name);
    }
}
