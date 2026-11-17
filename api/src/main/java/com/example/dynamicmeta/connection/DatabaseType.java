package com.example.dynamicmeta.connection;

/** Supported database types and their identifier strings. */
public enum DatabaseType {

    POSTGRESQL("postgresql"),
    MYSQL("mysql"),
    MARIADB("mariadb"),
    SQLSERVER("sqlserver"),
    ORACLE("oracle"),
    H2("h2");

    private final String id;

    DatabaseType(String id) {
        this.id = id;
    }

    /** Returns the dialect identifier used throughout the project. */
    public String id() {
        return id;
    }

    /** Looks up a DatabaseType by its id string (case-insensitive). */
    public static DatabaseType fromId(String id) {
        for (DatabaseType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}
