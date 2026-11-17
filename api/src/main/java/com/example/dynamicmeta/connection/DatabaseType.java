package com.example.dynamicmeta.connection;

/** Supported database types and their identifier strings. */
public enum DatabaseType {

    POSTGRESQL("postgresql", "org.postgresql.Driver"),
    MYSQL("mysql", "com.mysql.cj.jdbc.Driver"),
    MARIADB("mariadb", "org.mariadb.jdbc.Driver"),
    SQLSERVER("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    ORACLE("oracle", "oracle.jdbc.OracleDriver"),
    H2("h2", "org.h2.Driver");

    private final String id;
    private final String driverClassName;

    DatabaseType(String id, String driverClassName) {
        this.id = id;
        this.driverClassName = driverClassName;
    }

    /** Returns the dialect identifier used throughout the project. */
    public String id() {
        return id;
    }

    /** Returns the fully-qualified JDBC driver class name for this database type. */
    public String getDriverClassName() {
        return driverClassName;
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
