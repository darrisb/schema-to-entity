package com.example.dynamicmeta.data;

/**
 * Thrown when a detail lookup by single-column key finds no row.
 * <p>
 * The message contains only the entity name; it never includes a URL,
 * credentials or the caller-supplied key.
 */
public class RowNotFoundException extends RuntimeException {

    public RowNotFoundException(String entity) {
        super("No row found in entity '" + entity + "' for the requested key");
    }
}
