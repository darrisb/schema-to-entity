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
```

`/api/schema/sample` returns a fixed schema and needs no database, so the API can be demonstrated before anything is connected.

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
  schema-cache-ttl: 10m
  # Origins allowed to call the read-only data API from a browser. Empty
  # (default) sends no CORS headers. '*' is allowed only when written
  # explicitly.
  data-allowed-origins:
    - https://app.example.com
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
      view-keys:                      # view name -> key column (viewKeys)
        order_summary: order_id
      hidden-columns:                 # never returned, filtered or listed
        - password_hash               # bare name: hidden in every table and view
        - customers.tax_id            # entity.column: hidden in one entity
```

### Configuration

| Property | Default | Description |
|---|---|---|
| `schema-to-entity.data-api-enabled` | `false` | Registers the read-only data API and its endpoints. |
| `schema-to-entity.graph-enabled` | `false` | Builds the combined schema document and registers the `/api/graph/**` endpoints. Requires `data-api-enabled`. See "Combined schema document" below. |
| `schema-to-entity.overlay-file` | `./schema-overlay.json` | JSON file keeping the graph's overlay edits. See "The overlay file" below. |
| `schema-to-entity.sources` | `{}` | Named read-only sources, keyed by source name. |
| `schema-to-entity.max-page-size` | `200` | Largest `limit` a caller may request. |
| `schema-to-entity.default-page-size` | `50` | Rows returned when the caller omits `limit`. |
| `schema-to-entity.query-timeout` | `15s` | Maximum time a single data query may run. |
| `schema-to-entity.schema-cache-ttl` | `10m` | How long a source's discovered schema is cached. |
| `schema-to-entity.data-allowed-origins` | `[]` | Browser origins allowed to call `/api/data/**`. Empty sends no CORS headers. |

Each source accepts:

| Source property | Description |
|---|---|
| `url` | JDBC URL, parsed and checked by the same `ConnectionPolicy` as explicit connections. |
| `username` / `password` | Credentials. The password should come from an environment placeholder such as `${ORDERS_DB_PASSWORD}`. |
| `schema` | Schema to discover. |
| `tables` | Optional allowlist of tables/views; empty means everything in the schema. |
| `ssl` | `true` to require SSL. |
| `max-pool-size` | Pool size, 1-20, default 4. |
| `view-keys` (`viewKeys`) | View name to key column, used for views that have no primary key. |
| `hidden-columns` (`hiddenColumns`) | Columns the data API never returns, filters on or lists. A bare name hides the column in every table and view of the source; `entity.column` hides it in one entity. Matching ignores case. Hiding a key column leaves that entity without a key, so it can be listed but not fetched by key. |

Source names must match `[a-z0-9-]{1,40}`. Each URL is parsed and checked by the same `ConnectionPolicy` as explicit connections, so embedded databases still require `allow-embedded-databases: true`. Invalid sources fail startup with an `IllegalStateException` that names the source and the problem; error messages never include the URL or password.

The data API is **READ-ONLY**: it only ever issues `SELECT` and `SELECT COUNT(*)`. It never generates `INSERT`, `UPDATE`, `DELETE` or DDL, and every pool is opened with the read-only flag.

### Database accounts

Give each source a database account with **SELECT-only grants** on the schemas and tables it may read. The API never writes, so the account does not need (and should not have) `INSERT`, `UPDATE`, `DELETE` or DDL privileges. Never configure a source with an account that owns or can modify the data.

Browser access is controlled by `schema-to-entity.data-allowed-origins` (default empty, meaning no CORS headers). List origins explicitly, or write `*` to allow any origin. The mapping covers `/api/data/**` only, allows `GET` and `OPTIONS` without credentials, caches preflight responses for one hour, and exposes the `X-Schema-Version` header so browsers can read it; admin endpoints such as `/api/schema/extract` never receive CORS headers.

Each source's schema is discovered once and cached as the typed query allowlist (`SchemaCache`). The cache honours the source's `tables` allowlist and `view-keys`, and re-discovers automatically after `schema-to-entity.schema-cache-ttl` (default `10m`); `SchemaCache.refresh(source)` forces rediscovery.

### Endpoints

All endpoints are `GET` and return JSON. Every successful response carries an `X-Schema-Version` header naming the schema version its data was read from: the first 12 hex characters of the current snapshot fingerprint for the source, held alongside the cached schema so it is computed once per discovery rather than per request. It changes only when the cache starts serving a different structure (an expired `schema-cache-ttl` or a `SchemaCache.refresh`). The entity listing repeats the same value in its JSON body. Error responses carry no version, since nothing was served. Errors raised by the data API are returned as `{"error": "..."}` with the message passed through the secret redactor; unmapped methods or paths receive Spring's default error response.

#### `GET /api/data/{source}` — list the entities of a source

```bash
curl http://localhost:8080/api/data/demo
```

Returns a JSON object with the `schemaVersion` and an `entities` array of entity descriptors:

```json
{
  "schemaVersion": "1a2b3c4d5e6f",
  "entities": [
    {
      "name": "CUSTOMERS",
      "kind": "table",
      "keyColumns": ["ID"],
      "columns": [
        { "name": "ID", "type": "bigint", "category": "NUMBER", "nullable": false },
        { "name": "NAME", "type": "varchar(80)", "category": "TEXT", "nullable": false }
      ]
    }
  ]
}
```

**Breaking response-shape change (DR-008):** this endpoint previously returned the bare JSON array of entity descriptors; the array now travels under `entities` next to `schemaVersion`. Clients that parsed the top-level array must read `entities`. Nothing in this repository consumes the listing over HTTP (the demo application and the Angular UI never call `GET /api/data/{source}`), so no in-repo consumer needed updating; deployed external clients are affected and the new shape is recorded here as a deliberate breaking change.

#### `GET /api/data/{source}/{entity}` — query a page of rows

```bash
curl "http://localhost:8080/api/data/demo/customers?fields=id,name&sort=-id&limit=2&offset=1&eq.active=true&contains.name=ar&total=false"
```

Returns the `items` envelope:

```json
{
  "items": [
    { "ID": 2, "NAME": "Bob" }
  ],
  "total": 5,
  "limit": 2,
  "offset": 1
}
```

`total` is `null` when `total=false` is sent, and `offset` is `null` when the caller did not send one.

Values keep their JSON types, with two exceptions that protect browser clients:

- Whole numbers larger than JavaScript's safe integer range (2^53 - 1, for example big `BIGINT` keys) are returned as strings, so a browser cannot round them to a different value.
- Timestamps without a time zone are returned as local date-times (`2026-01-15T10:30:00`), exactly as stored; timestamps with a time zone keep their offset. Dates are `yyyy-MM-dd`. Binary columns are `null`.

#### `GET /api/data/{source}/{entity}/{key}` — fetch one row by key

```bash
curl http://localhost:8080/api/data/demo/customers/1
```

Returns the row as a flat JSON object, or `404` when the key does not match. Entities without a single-column key (for example a view with no `view-keys` entry) return `400`.

### Query parameters

| Parameter | Meaning |
|---|---|
| `fields=a,b` | Select columns in request order. Omitted or blank selects every column. |
| `sort=a,-b` | Order by `a` ascending, then `b` descending. A leading `-` means descending; `+` or no prefix means ascending. At most 3 sort columns. |
| `limit=n` | Page size. Defaults to `default-page-size`; must be between 1 and `max-page-size`. |
| `offset=n` | Rows to skip. Must be between 0 and 1,000,000. |
| `eq.<column>=value` | Exact-match filter. The value is converted from the column's type. |
| `contains.<column>=text` | Case-insensitive substring filter; only text columns are accepted. |
| `total=false` | Skip the `COUNT(*)` and return `"total": null`. |

Unknown parameters, unknown columns, more than 10 filters, more than 3 sort columns, duplicate filters and values that cannot be converted for the column type are rejected with `400`. Only `GET` is mapped; other methods receive `405`.

### Status codes

| Status | When |
|---|---|
| **200** OK | The request succeeded. |
| **400** Bad Request | Unknown parameter or column, invalid value, invalid paging, or a key lookup on an entity without a single-column key. |
| **404** Not Found | Unknown source, unknown entity, or no row for the requested key. |
| **405** Method Not Allowed | A non-`GET` request to a data endpoint. |
| **502** Bad Gateway | The database read or schema discovery failed. |
| **503** Service Unavailable | No pooled connection became available in time. |
| **504** Gateway Timeout | The query exceeded `query-timeout`. |

For `502`, `503` and `504` the response carries a generic message (for example `{"error": "Database query failed"}`). The database driver's own message can name tables or quote SQL, so it is written to the server log instead of being returned to callers.

### UI data source

The row envelope is designed for a UI library REST data source. Point it at `/api/data/demo/<entity>?fields=id,name` and read the rows from `items` with `responsePath`:

```ts
const customersDataSource = {
  url: '/api/data/demo/customers?fields=id,name',
  responsePath: 'items'
};
```

Replace `customers` with any entity name returned by `GET /api/data/demo`. The data source receives the same `{ items, total, limit, offset }` envelope shown above, so paging and total counts are available when the library supports them.

## Combined schema document (graph)

The `com.example.dynamicmeta.graph` package builds one JSON document describing **every configured source at once**: the sources, the entities and columns discovered in them, the relationships between them — including links proposed across sources — and the ids a reviewer's edits no longer match. The document is built from the same discovered schemas the data API caches, so the graph needs `data-api-enabled: true` as well.

```yaml
schema-to-entity:
  # Off by default: the document names every configured source and the
  # endpoints accept edits. See the security note at the end of this
  # section.
  graph-enabled: true
  # Where the overlay edits are kept (default: ./schema-overlay.json).
  overlay-file: ./schema-overlay.json
```

The demo application ships with `graph-enabled: true` and two sources (`demo` in schema `public`, `billing` in schema `billing`, both in the compose Postgres), so the document shows a declared foreign key inside `demo`, one inside `billing` and a cross-source proposal between them. Note that `db/init/02-billing.sql` only runs on an empty Postgres data directory — see Known Issues #5 (`docker compose down -v` first).

### What the document contains

| Field | Meaning |
|---|---|
| `specVersion` | Layout version of the document, currently `1.0`. |
| `generatedAt` | When the snapshot was taken. |
| `sources` | Every configured source, with `databaseType`, `catalog`, `schema` and `status` — `ok`, or `unavailable` with a generic `error` (never a driver message) when discovery failed. One unavailable source does not break the document; the rest is still included. |
| `entities` | Every discovered table, view or materialized view across all sources, each with its `keyColumns`, its `columns` and, for views, its `baseTables`. `label` and `description` carry the overlay's display names and are null without one. |
| `relationships` | Every relationship across all sources. See the next table. |
| `orphans` | Overlay ids that no longer match anything — a dropped table, say. They stay in the overlay so a later rediscovery that brings the table back reattaches the edit. |

The document is a snapshot: it describes the sources, and nothing in it writes to any source database. The only file the graph writes is the overlay JSON.

### Ids

Every entity, column and relationship carries a stable, lower-case id so it can be referenced from anywhere in the document and from the overlay file:

| Thing | Id format | Example |
|---|---|---|
| Entity | `source:entity` | `demo:users` |
| Column | `source:entity.column` | `demo:users.id` |
| Relationship | `fromColumnId->toColumnId` | `demo:posts.author_id->demo:users.id` |

Names are lower-cased with the root locale, so an id is stable no matter how the database reports the casing of its names. Source, entity and column names must not contain `:` or `.` — names reported by JDBC metadata never do; ids are sent in bodies and query parameters, never path segments, because `:` and `->` do not survive path segmentation unambiguously.

### Relationships: origin, scope, status, confidence

| Field | Values | Meaning |
|---|---|---|
| `origin` | `declared` | Read from the database's own foreign keys. |
| | `inferred` | Found by analysis: a view column named like a base table's key within one source, or a `*_id` / `…Id` column whose stem matches another source's entity name. Columns in a composite key (such as `order_items(order_id, product_id)`) are candidates too; only a table's single-column key is skipped. `…Id` (camelCase) is only recognised when the database keeps the column's case: PostgreSQL folds unquoted names to lower case (`customerid`), so name such columns `customer_id` or quote them. |
| | `manual` | Asserted by a person through the overlay endpoints. |
| `scope` | `within-source` | Both ends live in the same source. |
| | `cross-source` | The ends live in different sources. |
| `status` | `declared` | The database itself asserts the link; it needs no review and the overlay cannot change it. |
| | `proposed` | Awaiting review. **Cross-source links are always proposals** — no source's metadata knows another's tables, so a link between two sources can only be proposed by name matching, never discovered the way a foreign key is. |
| | `confirmed` | A reviewer accepted a proposal. |
| | `rejected` | A reviewer declined it; hidden from the document unless `includeRejected=true`. |
| `confidence` | `0.0`–`1.0` or null | How strongly the analysis supports an **inferred** relationship; always null for `declared`. It is a hint for ordering review work, not a probability: a within-source view inference scores `0.9` (evidence `view-column-name`); a cross-source proposal starts at `0.6`, gains `0.2` when the target's key is named `id` or like the column, and is capped at `0.8` (evidence `name-match`, `type-match`, `key-name-match`). Manual relationships have none. |
| `evidence` | list | The tokens or ids the relationship was derived from. |

**Cross-source links are proposals until confirmed.** Only `declared` and `confirmed` relationships are established facts; a UI should only rely on declared or confirmed relationships and treat `proposed` ones as review suggestions, not as connections it can navigate.

### Example

An abbreviated `GET /api/graph` for the demo's two sources, trimmed to one entity and two relationships (a real response lists every column of every entity of both sources):

```json
{
  "specVersion": "1.0",
  "generatedAt": "2026-11-24T09:15:00Z",
  "sources": [
    { "id": "billing", "databaseType": "postgresql", "catalog": null, "schema": "billing", "status": "ok", "error": null },
    { "id": "demo", "databaseType": "postgresql", "catalog": null, "schema": "public", "status": "ok", "error": null }
  ],
  "entities": [
    {
      "id": "demo:users", "source": "demo", "name": "users", "kind": "table",
      "label": null, "description": null, "keyColumns": ["id"],
      "columns": [
        { "id": "demo:users.id", "name": "id", "type": "bigserial", "category": "NUMBER", "nullable": false, "label": null, "description": null },
        { "id": "demo:users.username", "name": "username", "type": "varchar(255)", "category": "TEXT", "nullable": false, "label": null, "description": null }
      ],
      "baseTables": []
    }
  ],
  "relationships": [
    {
      "id": "billing:invoices.user_id->demo:users.id",
      "from": "billing:invoices.user_id", "to": "demo:users.id",
      "origin": "inferred", "scope": "cross-source", "status": "proposed",
      "confidence": 0.8,
      "evidence": ["name-match", "type-match", "key-name-match"]
    },
    {
      "id": "demo:posts.author_id->demo:users.id",
      "from": "demo:posts.author_id", "to": "demo:users.id",
      "origin": "declared", "scope": "within-source", "status": "declared",
      "confidence": null, "evidence": []
    }
  ],
  "orphans": []
}
```

### The overlay file

Edits a reviewer makes — labels and descriptions for entities and columns, confirm/reject decisions on proposals, manual relationships — are kept **apart from discovery** in one JSON file, `schema-to-entity.overlay-file` (default `./schema-overlay.json`). Keeping them separate is what makes rediscovery safe: the schema can be re-discovered as often as the cache expires without ever wiping what a person decided, and an edit whose table was dropped waits in the overlay (reported as `orphans`) until the table comes back. The file is pretty-printed and written atomically (temporary file, then moved over), and it is the **only** file this starter writes itself; security settings such as hidden columns stay in configuration, never in the overlay. It is a local file path, not a shared store: running more than one instance of the host application gives each instance its own overlay. The folder is created if it does not exist. In Docker, point `overlay-file` at a mounted volume (the bundled `docker-compose.yml` uses `/data/schema-overlay.json` on the `overlay_data` volume); otherwise edits are lost when the container is rebuilt, and a non-root container user may not be able to write next to the application jar at all.

### Endpoints

All endpoints live under `/api/graph` and return JSON; errors are always `{"error": "..."}` — an unknown id is `404`, invalid input `400`. Every id travels in a body or a query parameter, never a path segment.

#### `GET /api/graph` — the combined document

```bash
curl 'http://localhost:8080/api/graph?sources=demo,billing&includeRejected=false'
```

The built document with the overlay applied, so every read reflects the stored edits. `sources` is a comma-separated list limiting the build; omit it for every configured source. An unknown source name returns `404`. `includeRejected=true` keeps rejected relationships in the response (default `false`).

#### `GET /api/graph/relationships` — just the relationships

```bash
curl 'http://localhost:8080/api/graph/relationships?status=proposed'
```

The relationships of the document, optionally filtered by `status=declared|proposed|confirmed|rejected`. Unlike the document, rejected relationships are **listed by default** here, so `status=rejected` can show them; pass `includeRejected=false` to hide them. An unknown status is `400`.

#### `PUT /api/graph/overlay/relationships` — confirm or reject

```bash
curl -X PUT http://localhost:8080/api/graph/overlay/relationships \
  -H 'Content-Type: application/json' \
  -d '{"id": "billing:invoices.user_id->demo:users.id", "status": "confirmed"}'
```

Confirms or rejects a relationship that is in the document; `"status": null` clears the decision and returns it to `proposed`. Returns `204`. Deciding has no effect on a `declared` relationship — the database asserts those — but the decision is still stored, so the id is not reported as an orphan. Unknown id → `404`, anything but `confirmed`/`rejected`/null → `400`.

#### `POST /api/graph/overlay/relationships` — assert a manual relationship

```bash
curl -X POST http://localhost:8080/api/graph/overlay/relationships \
  -H 'Content-Type: application/json' \
  -d '{"from": "demo:users.id", "to": "billing:invoices.customer_id"}'
```

Asserts a manual relationship between two column ids that both exist in the document — typically a cross-source link discovery could not know — and returns its relationship id: `{"id": "demo:users.id->billing:invoices.customer_id"}`. A manual relationship enters the document as `manual`/`confirmed` with no confidence. Asserting the same pair again changes nothing. Unknown column → `404`, an end that is not a column id → `400`.

#### `DELETE /api/graph/overlay/relationships?id=…` — remove

```bash
curl -G -X DELETE http://localhost:8080/api/graph/overlay/relationships \
  --data-urlencode 'id=demo:users.id->billing:invoices.customer_id'
```

Removes the manual relationship and/or the decision the overlay holds for that id (or both). Returns `204`, or `404` when the overlay holds neither. Ids passed as query parameters must be URL-encoded — a raw `>` in the id (as in `demo:users.id->billing:invoices.customer_id`) is rejected by the server with `400` before the request reaches the endpoint, so encode it as `%3E` or let `curl --data-urlencode` do it.

#### `PUT /api/graph/overlay/labels` — label an entity or column

```bash
curl -X PUT http://localhost:8080/api/graph/overlay/labels \
  -H 'Content-Type: application/json' \
  -d '{"id": "demo:users", "label": "Users", "description": "Registered accounts"}'
```

Labels the entity or column id, replacing any previous label for it. The label is required (at most 200 characters); the description is optional (at most 2000, may be null). Returns `204`; unknown id → `404`, blank label → `400`.

#### `DELETE /api/graph/overlay/labels?id=…` — remove a label

```bash
curl -X DELETE 'http://localhost:8080/api/graph/overlay/labels?id=demo:users'
```

Removes the label of the id. Returns `204`, or `404` when neither the document nor the overlay knows the id.

**Security:** the graph endpoints are admin endpoints, like `/api/schema/**`: the document names every configured source and the overlay endpoints accept edits, so they receive no CORS headers — only `/api/data/**` does — and have no authentication of their own. `graph-enabled` is off by default for that reason; keep it off, or behind authentication and authorization, outside local development. Nothing here writes to any source database; the only file written is the overlay JSON.

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
