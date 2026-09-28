# Schema to Entity Spring Boot Starter

This module is a normal Spring Boot library JAR. When it is added to an application it discovers that application's existing `DataSource`; it does not create or replace one and it does not ship datasource configuration.

## Install locally

```bash
cd api
mvn install
```

Then add it to a Spring Boot 3 application:

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>schema-to-entity-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

Use the database driver that matches the host application. For example, a PostgreSQL application should already depend on `org.postgresql:postgresql`.

## Automatic datasource discovery

Configure the host application normally with `spring.datasource.*`. The starter auto-configures `SchemaDiscoveryService`, which can be injected directly:

```java
public MyService(SchemaDiscoveryService schemas) {
    this.schemas = schemas;
}

Map<String, Object> schema = schemas.discover();
```

If the application is a servlet web application, these endpoints are also added:

```text
GET /api/schema
GET /api/schema/datasource?schema=public
```

Both use the host application's `DataSource`.

## Explicit URL

The existing URL workflow remains available:

```http
POST /api/schema/extract-postgres
Content-Type: application/json

{
  "connectionString": "postgresql://user:password@localhost:5432/my_database",
  "schemaName": "public",
  "tables": ["users", "orders"],
  "ssl": true
}
```

The generic alias `POST /api/schema/extract` also accepts a JDBC URL. `username`, `password`, and `driverClassName` are optional request fields. One-off connections never replace the host `DataSource`.

## Settings

```yaml
schema-to-entity:
  enabled: true
  web-enabled: true
  explicit-connections-enabled: true
  schema: public
```

Set `web-enabled: false` to use only the Java service. In production, consider setting `explicit-connections-enabled: false` unless users must be allowed to submit database addresses and credentials.
