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
GET  /api/schema
GET  /api/schema/datasource?schema=public
GET  /api/schema/sample
POST /api/schema/to-spring-metadata
```

Both use the host application's `DataSource`. `/api/schema/sample` returns a fixed schema and needs no database, so the API can be demonstrated before anything is connected.

`/api/schema/to-spring-metadata` turns a discovered schema into Spring dynamic-entity metadata:

```http
POST /api/schema/to-spring-metadata
Content-Type: application/json

{
  "schema": { "dialect": "postgresql", "tables": [] },
  "options": { "entityNamePrefix": "Dynamic" }
}
```

It returns `schemaVersion`, `dialect`, `generatedAt` and `entities`, where each entity carries a `primaryKey` (single or composite), `properties` and `relationships`. `entityNamePrefix` is optional and defaults to `Dynamic`.

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
  # Off by default. See the security note below.
  explicit-connections-enabled: false
  schema: public
  # Include database views and materialized views alongside tables (default: true).
  include-views: true
  # How long a named source's discovered schema is cached before rediscovery
  # (default: 10m).
  schema-cache-ttl: 10m
  # Origins allowed to call the read-only data API from a browser. Empty
  # (default) sends no CORS headers. '*' is allowed only when written
  # explicitly.
  data-allowed-origins:
    - https://app.example.com
```

Set `web-enabled: false` to use only the Java service.

**Security:** explicit connections are **disabled by default** (`explicit-connections-enabled: false`). They let any caller submit database addresses and credentials, so the endpoint has no authentication of its own. Opt in with `explicit-connections-enabled: true` only for local development, or when the endpoint is protected by authentication and authorization. Outside local development it must stay off, or be behind authentication.

## Named data sources (read-only data API)

Generated UIs run in browsers and cannot send database credentials, so the data API reads from sources registered on the server by name. The sources are validated at startup; this is configuration only and nothing connects yet.

```yaml
schema-to-entity:
  data-api-enabled: false
  max-page-size: 200
  default-page-size: 50
  query-timeout: 15s
  sources:
    orders:
      url: jdbc:postgresql://orders-db:5432/orders
      username: orders_reader
      # Passwords should come from environment placeholders such as
      # ${ORDERS_DB_PASSWORD}; never commit literal passwords.
      password: ${ORDERS_DB_PASSWORD}
      schema: public
      tables: [orders, order_items]   # optional allowlist
      ssl: true
      max-pool-size: 4                # 1-20
      view-keys:                      # view name -> key column
        order_summary: order_id
```

Source names must match `[a-z0-9-]{1,40}`. Each URL is parsed and checked by the same `ConnectionPolicy` as explicit connections, so embedded databases still require `allow-embedded-databases: true`. Invalid sources fail startup with an `IllegalStateException` that names the source and the problem; error messages never include the URL or password.

The data API is **READ-ONLY**: it never issues `INSERT`, `UPDATE`, `DELETE` or DDL.

Browser access is controlled by `schema-to-entity.data-allowed-origins` (default empty, meaning no CORS headers). List origins explicitly, or write `*` to allow any origin. The mapping covers `/api/data/**` only, allows `GET` and `OPTIONS` without credentials, and caches preflight responses for one hour; admin endpoints such as `/api/schema/extract` never receive CORS headers.

Each source's schema is discovered once and cached as the typed query allowlist (`SchemaCache`). The cache honours the source's `tables` allowlist and `view-keys`, and re-discovers automatically after `schema-to-entity.schema-cache-ttl` (default `10m`); `SchemaCache.refresh(source)` forces rediscovery.

## Connection layer

The `com.example.dynamicmeta.connection` package provides a parser, policy engine and factory for opening one-off JDBC connections from caller-supplied strings. The drivers shipped by the demo application cover five database families.

### Supported connection string formats

The parser accepts both **JDBC URL** and **URI** forms. Credentials can be embedded in the URI or supplied as separate request fields.

| Database | JDBC URL form | URI form |
|---|---|---|
| PostgreSQL | `jdbc:postgresql://host:port/database?ssl=true` | `postgresql://user:pass@host:port/database` |
| MySQL | `jdbc:mysql://host:port/database?usessl=true` | `mysql://user:pass@host:port/database` |
| MariaDB | `jdbc:mariadb://host:port/database` | `mariadb://user:pass@host:port/database` |
| SQL Server | `jdbc:sqlserver://host:port;databaseName=db;encrypt=true` | `sqlserver://user:pass@host:port/database` |
| Oracle | `jdbc:oracle:thin:@//host:port/service` | (URI form not supported) |
| H2 | `jdbc:h2:mem:testdb` / `jdbc:h2:tcp://host:port/path` | (URI form not supported) |

Oracle does not accept a URI scheme because its JDBC URL format is not URI-compatible.

`user` and `password` may also be supplied as URL parameters (any case), for example `jdbc:postgresql://host/db?user=u&password=p`. They are removed from the URL and passed to the driver as connection properties, so the rebuilt JDBC URL never contains credentials.

The URL sent to the driver is always rebuilt from the parsed host, port, database and allowlisted parameters; the original string is never passed through. Two limits follow from that:

- Database, service and SID names may only contain letters, digits, `_`, `$`, `.` and `-` (up to 128 characters).
- SQL Server parameter values cannot contain `;`, `=` or braces, because the SQL Server driver does not decode escaped values.

### URL parameter allowlist

For security, only an allowlisted set of URL parameters may appear in a connection string. Any parameter not on the list causes the connection to be rejected with HTTP 403.

| Database | Allowed parameters |
|---|---|
| PostgreSQL | `ssl`, `sslmode`, `applicationname`, `currentschema` |
| MySQL / MariaDB | `usessl`, `sslmode`, `requiressl`, `servertimezone`, `characterencoding` |
| SQL Server | `encrypt`, `trustservercertificate`, `databasename`, `instancename`, `applicationname` |
| Oracle | *(none – only `@//host:port/service` and `@host:port:sid` are accepted; TNS descriptors, aliases and `?` parameters are rejected)* |
| H2 | `db_close_delay`, `db_close_on_exit`, `file_lock`, `cache_size`, `max_row_fetch`, `query_timeout`, `access_mode_data`, `compress`, `ifexists` |

Timeout parameters (`connecttimeout`, `sockettimeout`, `logintimeout`) are never allowed in the connection string. Connection and socket timeouts come only from the server settings `schema-to-entity.connect-timeout` and `schema-to-entity.socket-timeout`.

### Host allowlist (`allowed-hosts`)

A list of allowed host patterns. When empty (default), any host is permitted subject to the other policy checks below. Supports exact host match and `*.example.com` wildcard patterns.

Embedded databases (no host) are rejected when `allowed-hosts` is non-empty, because there is no host to match against.

### Embedded database rule

All `jdbc:h2:` URLs, including `mem:`, `file:`, `tcp://` and `ssl://`, are **rejected by default**. Set `allow-embedded-databases: true` to permit them.

### Timeouts

Two timeouts apply to every explicit connection. They are translated to the driver-specific property names:

| Property | Default | Description |
|---|---|---|
| `connect-timeout` | `10s` | Maximum time to wait for a TCP connection |
| `socket-timeout` | `60s` | Maximum idle time between two data packets |

Mapping per driver:

| Database | Connect timeout property | Socket timeout property |
|---|---|---|
| PostgreSQL | `connectTimeout` (seconds) | `socketTimeout` (seconds) |
| MySQL / MariaDB | `connectTimeout` (milliseconds) | `socketTimeout` (milliseconds) |
| SQL Server | `loginTimeout` (seconds) | `socketTimeout` (milliseconds) |
| Oracle | `oracle.net.CONNECT_TIMEOUT` (ms) | `oracle.jdbc.ReadTimeout` (ms) |

### Concurrency limit (`max-concurrent-explicit-connections`)

By default, at most **4** explicit connections are processed concurrently. Requests that cannot acquire a permit within 2 seconds receive HTTP 429 (Too Many Requests). Set to `0` or a negative value to allow unlimited concurrency (not recommended in production).

### Error status codes

| Status | When |
|---|---|
| **400** Bad Request | Missing or malformed request body; invalid connection string |
| **403** Forbidden | Host not in allowlist; forbidden metadata address; embedded DB not permitted |
| **422** Unprocessable Entity | Unsupported database type; JDBC driver not on the classpath |
| **429** Too Many Requests | Concurrency limit exceeded (permit not acquired within 2 s) |
| **502** Bad Gateway | Driver refused connection; connection opened but database returned an error |
| **504** Gateway Timeout | The database did not respond within the socket timeout |

All error responses are passed through a secret redactor that removes passwords and full connection strings from the output.

### Test-connection endpoint

```http
POST /api/schema/test-connection
Content-Type: application/json

{
  "connectionString": "postgresql://user:pass@localhost:5432/mydb"
}
```

Opens a one-off connection using the same parser, policy, factory and concurrency limit as `/api/schema/extract`, but does **not** read any table or column metadata. Returns:

```json
{
  "databaseType": "postgresql",
  "productName": "PostgreSQL",
  "productVersion": "15.19 (Debian 15.19-1.pgdg13+1)",
  "driverName": "PostgreSQL JDBC Driver",
  "driverVersion": "42.7.5",
  "readOnly": true,
  "latencyMs": 12
}
```
