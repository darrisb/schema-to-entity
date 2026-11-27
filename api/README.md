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
| `schema-to-entity.snapshot-dir` | `./snapshots` | Folder holding each source's accepted baseline snapshot. See "Drift detection" below. |
| `schema-to-entity.drift-check-interval` | *(unset)* | How often every source is checked for drift in the background. Unset schedules nothing. Requires `graph-enabled`. See "Drift detection" below. |
| `schema-to-entity.pages-enabled` | `false` | Registers the page storage API at `/api/pages/**`. See "Page storage" below. |
| `schema-to-entity.pages-dir` | `./pages` | Folder holding current page documents and their short history. |
| `schema-to-entity.page-allowed-origins` | `[]` | Browser origins allowed to call `/api/pages/**`. Empty sends no CORS headers. |

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

All endpoints are `GET` and return JSON. Every successful response carries an `X-Schema-Version` header naming the schema version its data was read from: the first 12 hex characters of the current snapshot fingerprint for the source, held alongside the cached schema so it is computed once per discovery rather than per request. It changes only when the cache starts serving a different structure (an expired `schema-cache-ttl` or a `SchemaCache.refresh`). Each entity descriptor in the listing repeats the same value as a `schemaVersion` field, so the listing's JSON shape is unchanged apart from that added field. Error responses carry no version, since nothing was served. Errors raised by the data API are returned as `{"error": "..."}` with the message passed through the secret redactor; unmapped methods or paths receive Spring's default error response.

#### `GET /api/data/{source}` — list the entities of a source

```bash
curl http://localhost:8080/api/data/demo
```

Returns a JSON array of entity descriptors. Each descriptor carries the source's `schemaVersion` (the same value as the `X-Schema-Version` header); clients that parse the bare array keep working:

```json
[
  {
    "name": "CUSTOMERS",
    "kind": "table",
    "keyColumns": ["ID"],
    "columns": [
      { "name": "ID", "type": "bigint", "category": "NUMBER", "nullable": false },
      { "name": "NAME", "type": "varchar(80)", "category": "TEXT", "nullable": false }
    ],
    "schemaVersion": "1a2b3c4d5e6f"
  }
]
```

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

The demo application ships with `graph-enabled: true` and two sources (`demo` in schema `public`, `billing` in schema `billing`, both in the compose Postgres), so the document shows a declared foreign key inside `demo`, one inside `billing` and a cross-source proposal between them. It also checks those sources for drift every 15 minutes, keeping the baselines at `/data/snapshots` on the same mounted volume as the overlay — see "Drift detection" below. Note that `db/init/02-billing.sql` only runs on an empty Postgres data directory — see Known Issues #5 (`docker compose down -v` first).

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

**Security:** the graph endpoints are admin endpoints, like `/api/schema/**`: the document names every configured source and the overlay endpoints accept edits, so they receive no CORS headers — only `/api/data/**` does — and have no authentication of their own. `graph-enabled` is off by default for that reason; keep it off, or behind authentication and authorization, outside local development. Nothing here writes to any source database; the only file written is the overlay JSON. The drift endpoints below live under `/api/graph/drift` and carry exactly the same admin standing, gating and error shape.

## Page storage

A **page** is a visual-ui JSON document: a JSON array of components produced by the visual-ui examples or builder. The server stores the document, extracts the database entities and columns it refers to, and compares those dependencies with the current schema. It never renders the page and no page operation writes to a source database.

Page storage is an opt-in admin API:

```yaml
schema-to-entity:
  pages-enabled: true
  pages-dir: ./pages
  page-allowed-origins:
    - http://localhost:5173
```

`pages-dir` contains `<id>.json` for each current page. Replacing a page archives the previous version under `history/<id>/`; the five newest versions are retained. Page ids must match `[a-z0-9-]{1,64}`, names are at most 120 characters, and a document is at most 512 KB. In Docker, use persistent storage: the bundled Compose configuration sets `PAGES_DIR=/data/pages` on the existing `overlay_data` volume.

### Page endpoints

#### `PUT /api/pages/{id}` — create or replace a page

```bash
curl -X PUT http://localhost:8080/api/pages/customer-admin \
  -H 'Content-Type: application/json' \
  -d '{"name":"Customer admin","description":"Customer list","document":[{"id":"users-table","type":"table","dataSource":{"type":"rest","url":"/api/data/demo/users","schemaVersion":"1a2b3c4d5e6f"},"properties":{"columns":"id|ID\nuser_name|Name"}}]}'
```

The request fields are `name`, `description` and `document`; `document` must be a JSON array. The response is the stored page with `id`, `name`, `description`, `document`, `createdAt` and `updatedAt`.

#### `GET /api/pages` — list pages

```bash
curl http://localhost:8080/api/pages
```

Returns summaries in id order. Each has `id`, `name`, `description`, `updatedAt`, an `entities` set, and `status`.

#### `GET /api/pages/{id}` — read a page

```bash
curl http://localhost:8080/api/pages/customer-admin
```

Returns the stored fields plus `dependencies`, whose `usages` entries contain
`componentId`, `kind`, `entityId` and the optional `columnId`, and whose
`schemaVersions` map records versions embedded in REST data sources.

#### `GET /api/pages/{id}/status` — check schema compatibility

```bash
curl http://localhost:8080/api/pages/customer-admin/status
```

Returns `pageId`, `state`, `missingEntities`, `missingColumns` and `staleSources`. Status meanings are:

| `state` | Meaning |
|---|---|
| `ok` | Every referenced entity and column exists, and recorded schema versions match. |
| `stale` | Dependencies still exist, but at least one recorded `schemaVersion` differs from the schema currently served. |
| `broken` | At least one referenced entity or column is missing. |
| `unavailable` | A referenced source could not be resolved or discovered, so compatibility could not be determined. |

#### `DELETE /api/pages/{id}` — delete a page

```bash
curl -X DELETE http://localhost:8080/api/pages/customer-admin
```

Returns `204` when deleted and `404` for an unknown id. Existing archived versions are retained.

### How dependencies are found

The analyzer walks every component and nested `children`. For REST data sources whose URL path matches `/api/data/{source}/{entity}`, it records the entity and reads columns from `mapping` values, `query.fields`, `eq.<column>`, `contains.<column>` and `sort`. It also reads a table's pipe-delimited `properties.columns`, and the `filterField` of input/dropdown components whose `filterTarget` names a table. A data source's `schemaVersion` is recorded per source. Malformed or unrelated components are ignored rather than making the page unreadable.

Drift reports use the same extracted dependencies. When drift removes or changes an entity or column, each matching `impact.pages` entry names the saved `pageId` and affected `componentIds`, allowing a reviewer to find pages that may need repair before accepting the new baseline. Drift analysis only reads page files.

Browser access is controlled separately by `schema-to-entity.page-allowed-origins`. Empty (the default) sends no CORS headers; list exact origins or explicitly use `*`. The mapping applies only to `/api/pages/**`, permits `GET`, `PUT`, `DELETE` and `OPTIONS` without credentials, and does not change CORS for data, graph, drift or schema endpoints.

**Security:** page endpoints have no authentication or authorization of their own and include write operations. Allowed origins are a browser CORS boundary, not authentication. Keep `pages-enabled` off outside local development unless the API is protected. Stored documents may describe UI structure and data dependencies, but page storage never renders them and never writes to a source database.

## Drift detection (snapshots, baselines and review)

The `com.example.dynamicmeta.drift` package answers one question: **has a source's structure changed since someone last agreed it looked right?** A check rediscovers a source, turns what it found into a snapshot, compares that snapshot with the accepted baseline and lists every difference with what each one affects, so a person can review the changes and accept the ones they agree with.

Drift detection is **one-way: database → JSON**. It reads metadata, it writes snapshot files, that is all. No part of it — a check, a report, an acceptance or a rename — ever sends SQL to a source database, and accepting drift applies nothing anywhere: it only makes the snapshot you were shown the new baseline. If a report says a column is missing, the DDL that adds it is yours to run with your own migration tooling; drift detection reports the outcome of the next check, it never causes one. The only files written are the baseline snapshots and, on a confirmed rename, the overlay.

```yaml
schema-to-entity:
  # Drift rides on the graph's gating: it reads the same discovered schemas the
  # data API caches, and its endpoints live under /api/graph/drift.
  data-api-enabled: true
  graph-enabled: true
  # Folder holding each source's accepted baseline (default: ./snapshots).
  snapshot-dir: ./snapshots
  # Unset by default, which schedules nothing: drift is then checked only when a
  # caller asks. When set, one background thread checks every source once per
  # interval, starting one interval after the application is ready.
  drift-check-interval: 15m
```

In Docker, point `snapshot-dir` at a mounted volume — the bundled `docker-compose.yml` puts it at `/data/snapshots` on the same `overlay_data` volume as the overlay — or the baselines are lost when the container is rebuilt, and a non-root container user may not be able to write next to the application jar at all.

### Fingerprints and baselines

`SnapshotBuilder` turns a discovered schema into a canonical `SchemaSnapshot`: entities, their columns and their declared foreign keys are sorted by lower-cased name, so the order a driver happened to report objects in never matters. The snapshot carries a **fingerprint** — the lower-case hex SHA-256 of a canonical string built from that sorted structure, 64 characters. Every field in the canonical string is tagged and length-prefixed (so no field boundary can blur into its neighbour) and it contains:

- entity names and kinds (`table`, `view`, `materialized-view`);
- column names, their types **as reported** — length and scale included — and their nullability;
- key columns, **in key order**: the order of a composite key is part of the structure;
- **declared** foreign keys (column, referenced table, referenced column).

It leaves out everything that is not structure: `takenAt`, the source name, the dialect, the type *category* (derived from the type name), overlay labels and descriptions, and inferred relationships. The fingerprint therefore **changes exactly when the structure changes** and stays identical when a snapshot is merely taken again.

The **baseline** is the snapshot a person last accepted, kept as one pretty-printed JSON file per source, `<snapshot-dir>/<source>.json`. Saving a new baseline first copies the one it replaces into `<snapshot-dir>/history/<source>/<takenAt>.json` (UTC, `yyyyMMdd'T'HHmmss'Z'`) and keeps the ten newest. Both writes are atomic (temporary file, then moved over), the folder is created when missing, and source names must match `[a-z0-9-]{1,40}`, so a crafted name cannot escape the folder. A baseline file that exists but cannot be read is an error that names the file: it is never deleted or overwritten automatically, because fixing it is a human decision.

**The first check of a source has nothing to compare against.** It stores the snapshot it just took as the baseline and reports `baseline-created` with no changes and both fingerprints equal — so drift is never reported against a schema nobody has agreed to yet, and the reviewer's job starts at the *second* check. A failed first check records nothing: it reports `unavailable` with a null baseline, and the next successful check creates the baseline as usual.

A report is remembered in memory per source, so reading drift costs no database round-trip; `?check=true` (and a source that has never been checked in this process) runs a fresh check instead. Reports are in-memory only: a restart clears them while the baselines on disk remain, so the first read after a restart checks the source again — and an accept before any check has run is refused with `409`.

### What a check reports

| `status` | When | What the report carries |
|---|---|---|
| `unchanged` | the current snapshot fingerprints exactly like the baseline | `changes` and `impact` are empty; both fingerprints are equal |
| `changed` | the fingerprints differ | `changes` lists every difference, `impact` one entry per change |
| `baseline-created` | first check of a source with no baseline on disk | `changes` is empty; `baselineFingerprint` equals `currentFingerprint` |
| `unavailable` | the schema could not be discovered | `currentFingerprint` is null, `changes` is empty, `error` is the generic `Schema discovery failed` — never the driver's message, which can quote a JDBC URL or a password. The baseline is still reported, and an unavailable source never overwrites it. |

| Field | Meaning |
|---|---|
| `source` | the source that was checked |
| `status` | one of the four names above (`baseline-created` is the wire spelling of the first) |
| `baselineFingerprint` | the accepted baseline this check compared against, null when there is none |
| `currentFingerprint` | the snapshot taken now, null when `unavailable` |
| `baselineTakenAt` | when the baseline snapshot was taken, null when there is none |
| `checkedAt` | when this check ran |
| `changes` | the differences from the baseline, sorted by entity, then column, then change type |
| `impact` | what each change affects — one entry per `changes` entry, in the same order |
| `error` | the generic reason only, for `unavailable`; null otherwise |

### Change types and severities

Every difference is a `SchemaChange` with a `type`, a `severity`, the `entity` it belongs to (spelled the way the snapshot it was read from spells it — for a change inside an entity that exists in both snapshots, that is the baseline's spelling), a `column` for column-level changes (null for entity-level ones) and short human-readable `before` and `after` states — either may be null when the change only adds or only removes something. Severities follow what a reader of the data would notice:

| Severity | Meaning |
|---|---|
| `ADDITIVE` | the schema gained something; existing data and existing readers are unaffected |
| `BREAKING` | something existing consumers rely on is gone or tightened |
| `REVIEW` | not safe to classify from metadata alone — a person should look |

| Type | Severity | What it means | `before` → `after` |
|---|---|---|---|
| `ENTITY_ADDED` | `ADDITIVE` | a table or view the baseline did not have | null → kind (`table`, `view`, …) |
| `ENTITY_REMOVED` | `BREAKING` | a table or view that is gone; its columns are **not** filed individually, because it is the table that disappeared | kind → null |
| `ENTITY_KIND_CHANGED` | `BREAKING` | a table that is now a view, or a view that is now a table | kind → kind |
| `ENTITY_RENAME_CANDIDATE` | `REVIEW` | a removed entity whose columns mostly match an added entity's — see "Rename candidates" | kind → kind, `relatedTo` names the added entity |
| `COLUMN_ADDED` | `ADDITIVE` when nullable, `BREAKING` when it demands a value | a column the baseline did not have | null → `varchar(40) nullable` |
| `COLUMN_REMOVED` | `BREAKING` | a column that is gone | `varchar(40) nullable` → null |
| `COLUMN_TYPE_CHANGED` | graded, see below | a column reported with a different type | `varchar(80)` → `varchar(120)` |
| `COLUMN_NULLABILITY_CHANGED` | `ADDITIVE` when the column became nullable, `BREAKING` when it became `NOT NULL` | a column that changed between NULL allowed and NULL forbidden | `nullable` → `not null` |
| `COLUMN_RENAME_CANDIDATE` | `REVIEW` | a removed column whose type matches an added column of a similar name — see "Rename candidates" | `varchar(40) nullable` → `varchar(40) nullable`, `relatedTo` names the added column |
| `KEY_COLUMNS_CHANGED` | `BREAKING` | the entity's key columns, or their order, are different | `id` → `tenant_id, id` (`none` when there is no key) |
| `FOREIGN_KEY_ADDED` | `ADDITIVE` | a declared foreign key the baseline did not have | null → `users.id` |
| `FOREIGN_KEY_REMOVED` | `BREAKING` | a declared foreign key that is gone; a key that kept its column but points elsewhere reads as the old one removed and the new one added | `users.id` → null |

A type change is graded by how far it moves:

- across type categories (`bigint` → `varchar`) — `BREAKING`;
- within one category, the same family with more room (`varchar(80)` → `varchar(120)`) — `ADDITIVE`, and less room (`varchar(120)` → `varchar(80)`) — `BREAKING`;
- anything else in one category — `REVIEW`: `int` → `bigint`, `date` → `timestamp`, an unbounded side (`varchar` → `varchar(120)`), or a scale-only move (`decimal(10,2)` → `decimal(10,4)`). It may be harmless, but metadata cannot say.

Types are compared case- and whitespace-insensitively, so a driver that spells the same type differently (`VARCHAR (80)`) reports no change at all.

### Rename candidates

A rename cannot be told from a drop-and-create by metadata alone, so it is **never certain and never replaces anything**: `COLUMN_RENAME_CANDIDATE` and `ENTITY_RENAME_CANDIDATE` come alongside the removal and the addition they pair up, and `relatedTo` names the other side so a reader can see what is matched with what.

- Two columns of the same entity are a candidate when they have the **same type** and their lower-cased names either contain one another or are at least `0.6` similar, where similarity is one minus the edit distance over the longer name.
- Two entities, one removed and one added, are a candidate when they share at least `0.8` of their column names — the shared count over the larger of the two column sets, so an entity cannot look like a rename of a much bigger one.

What a reviewer does with a candidate is confirm it: `POST /api/graph/drift/{source}/renames` moves every overlay edit made for the old id to the new id, which is what stops the labels, decisions and manual relationships the reviewer wrote from showing up as lost after the table or column is renamed in the database. The report itself keeps listing the removal and the addition — the candidate is a hint, and the rename is the decision.

### Impact: what each change affects

A `changed` report carries one `ChangeImpact` per change, in the same order, naming everything in the overlay and the baseline that refers to the changed entity or column, so a reviewer sees the consequences **before** accepting:

| Field | Holds |
|---|---|
| `change` | the change this entry is about (the same object as the matching `changes` entry) |
| `labels` | entity or column ids the overlay has a label for |
| `decisions` | relationship ids the overlay has a `confirmed` or `rejected` decision for |
| `manualRelationships` | relationship ids a person asserted by hand over this entity or column |
| `foreignKeys` | the baseline's declared foreign keys, as relationship ids, with this column or entity at either end |
| `pages` | saved page ids and the component ids in each page that use this entity or column |

Ids are spelled exactly as in the graph document and the overlay file (`demo:users`, `demo:users.user_name`, `demo:posts.author_id->demo:users.id`), so anything listed here is something the reviewer can look up and edit. Matching is case-insensitive and scoped to the source the baseline names, so a change in one source never reports the identically named table of another. An entity-level change owns the entity id and everything below it (its columns among them); a column change owns exactly its own column id — dropping a column does not report the label of its table, because the table is still there. Relationship ids are checked on both sides of `->`. When saved pages are enabled, `pages` identifies each affected page and the components within it; otherwise that list is empty. A change nothing refers to still gets an entry, with empty lists; `impact` always lines up with `changes` by position. Reporting impact only **reads** the overlay and saved pages — it changes nothing.

### What is *not* drift

- **Hidden columns.** `hidden-columns` is applied during discovery, so a hidden column is already absent from the schema a snapshot is built from — on both sides of the comparison. A column you hide is invisible to the data API and to drift detection alike: drift never reports a hidden column as added, removed or retyped, because it is not part of the structure being compared. (One consequence worth knowing: a baseline taken while the column was visible still holds it, so the first check after you add it to `hidden-columns` reports `COLUMN_REMOVED`. Accepting that report re-baselines and the report goes quiet.)
- **Inferred relationships.** Name-matched proposals — view-key inferences and cross-source suggestions — can appear or disappear without the schema changing, so they are dropped before fingerprinting and never reach a snapshot. Confirming, rejecting or deleting a proposal is an overlay edit, not drift.
- **Overlay edits.** Labels, descriptions, decisions and manual relationships live in the overlay, not in the database; editing them changes no fingerprint.
- **Casing and spelling.** `USERS` and `users` are the same table; `VARCHAR (80)` and `varchar(80)` are the same type. Matching and hashing are done lower-cased, with the root locale.
- **Order and dialect.** Objects are sorted before comparison, and the driver's product name is not fingerprinted, so a source reported in a different order, or after a version bump that changed nothing structural, is `unchanged`.
- **Data.** Drift is structure only. Inserting, updating or deleting rows never shows up.

### Endpoints

All drift endpoints live under `/api/graph/drift`, come with `graph-enabled: true` and `data-api-enabled: true`, and return JSON. Errors are always `{"error": "..."}` with the message passed through the secret redactor: an unknown source is `404`, invalid input is `400`, and an acceptance that no longer matches what the last check showed — or that comes before any check — is `409`. They receive no CORS headers (only `/api/data/**` does) and have no authentication of their own.

#### `GET /api/graph/drift` — the last report of every source

```bash
curl http://localhost:8080/api/graph/drift
curl 'http://localhost:8080/api/graph/drift?check=true'
```

A JSON array of reports in source-name order. Without `check` each source serves the report the last check remembered, and a source that has never been checked in this process is checked now — which is what records its baseline. `?check=true` runs a fresh check of every source first, in name order.

#### `GET /api/graph/drift/{source}` — one source's report

```bash
curl http://localhost:8080/api/graph/drift/demo
curl 'http://localhost:8080/api/graph/drift/demo?check=true'
```

The remembered report, or a fresh check when the source has none or when `check=true` asks for one. An unknown source is `404`. A `changed` report reads like this (trimmed to two entities of the demo source):

```json
{
  "source": "demo",
  "status": "changed",
  "baselineFingerprint": "ba6644c4dc218a5a39d75091c9b6deae0c5bd1044031ac3a863b23b54566a487",
  "currentFingerprint": "03b3fe9c31a13f30ced50b2ceef6fa05d3a6537efe8e2ad06c5620d46d930adf",
  "baselineTakenAt": "2026-11-24T09:15:00Z",
  "checkedAt": "2026-11-25T09:30:00Z",
  "changes": [
    { "type": "FOREIGN_KEY_REMOVED", "severity": "BREAKING", "entity": "posts", "column": "author_id",
      "before": "users.id", "after": null, "relatedTo": null },
    { "type": "COLUMN_REMOVED", "severity": "BREAKING", "entity": "users", "column": "user_name",
      "before": "varchar(40) nullable", "after": null, "relatedTo": null },
    { "type": "COLUMN_RENAME_CANDIDATE", "severity": "REVIEW", "entity": "users", "column": "user_name",
      "before": "varchar(40) nullable", "after": "varchar(40) nullable", "relatedTo": "username" },
    { "type": "COLUMN_ADDED", "severity": "ADDITIVE", "entity": "users", "column": "username",
      "before": null, "after": "varchar(40) nullable", "relatedTo": null }
  ],
  "impact": [
    { "change": { "type": "FOREIGN_KEY_REMOVED", "severity": "BREAKING", "entity": "posts", "column": "author_id",
                   "before": "users.id", "after": null, "relatedTo": null },
      "labels": [], "decisions": ["demo:posts.author_id->demo:users.id"],
      "manualRelationships": [], "foreignKeys": ["demo:posts.author_id->demo:users.id"], "pages": [] },
    { "change": { "type": "COLUMN_REMOVED", "severity": "BREAKING", "entity": "users", "column": "user_name",
                   "before": "varchar(40) nullable", "after": null, "relatedTo": null },
      "labels": ["demo:users.user_name"], "decisions": [], "manualRelationships": [], "foreignKeys": [],
      "pages": [{ "pageId": "customer-admin", "componentIds": ["users-table"] }] },
    { "change": { "type": "COLUMN_RENAME_CANDIDATE", "severity": "REVIEW", "entity": "users", "column": "user_name",
                   "before": "varchar(40) nullable", "after": "varchar(40) nullable", "relatedTo": "username" },
      "labels": ["demo:users.user_name"], "decisions": [], "manualRelationships": [], "foreignKeys": [],
      "pages": [{ "pageId": "customer-admin", "componentIds": ["users-table"] }] },
    { "change": { "type": "COLUMN_ADDED", "severity": "ADDITIVE", "entity": "users", "column": "username",
                   "before": null, "after": "varchar(40) nullable", "relatedTo": null },
      "labels": [], "decisions": [], "manualRelationships": [], "foreignKeys": [], "pages": [] }
  ],
  "error": null
}
```

#### `POST /api/graph/drift/{source}/accept` — confirm what you were shown

```bash
curl -X POST http://localhost:8080/api/graph/drift/demo/accept \
  -H 'Content-Type: application/json' \
  -d '{"fingerprint": "03b3fe9c31a13f30ced50b2ceef6fa05d3a6537efe8e2ad06c5620d46d930adf"}'
```

Promotes the snapshot that fingerprint names to the source's new baseline and returns the fresh report that follows — `unchanged`, because the baseline now matches the schema. The fingerprint must be the `currentFingerprint` of the report you just read: a stale one means the schema moved while you were looking, and accepting then would accept something else, so the request is refused with `409` and you should read the newer report and accept that one. Accepting before anything has been checked is `409` too; a blank or missing `fingerprint` is `400`. Accepting writes a snapshot file and the archived previous baseline — nothing is sent to the database.

#### `POST /api/graph/drift/{source}/renames` — carry edits across a confirmed rename

```bash
curl -X POST http://localhost:8080/api/graph/drift/demo/renames \
  -H 'Content-Type: application/json' \
  -d '{"from": "demo:users.user_name", "to": "demo:users.username"}'
```

Moves every overlay edit made for one entity or column id of this source to its new id: labels, relationship decisions and the ends of manual relationships all follow the rename. Both ids must be well-formed entity or column ids **of the source in the path** — a relationship id, a blank id, or one naming another source is `400`; an unknown source is `404`. Returns `204`. Renaming an entity id moves its columns' edits along with it; an overlay label already held for the new id wins, so a rename never overwrites what someone wrote for the new name. The only file written is the overlay.

### The review-then-accept workflow

1. **Read the drift.** `GET /api/graph/drift?check=true` (or just `GET /api/graph/drift/{source}` for one source). With `drift-check-interval` set, a background check has usually done this for you and logged a warning naming each `changed` or `unavailable` source.
2. **Read the consequences.** For every change, look at its `impact` entry: the labels, decisions, manual relationships and baseline foreign keys that refer to it. Those are the edits you need to care about, and the ids are exactly the ones `GET /api/graph` and the overlay file use.
3. **Decide, per source:**
   - *Expected change* — you ran a migration and it is correct. Accept it in step 4.
   - *Rename* — confirm the candidate by moving the edits: `POST /api/graph/drift/{source}/renames` with the old and new ids. The overlay now spells things the way the database does, and the graph document shows the labels again.
   - *Not expected* — fix it properly: either change the database back (with your own migration tooling — nothing here does it for you) or accept the change as the new truth. Leaving it unaccepted keeps it reported on every check, which is the point.
   - *`unavailable`* — the source could not be reached or discovered. Nothing was written and the baseline is untouched; fix connectivity or configuration and check again.
4. **Accept:** `POST /api/graph/drift/{source}/accept` with the `currentFingerprint` of the report you reviewed. The baseline file is replaced (the previous one archived under `history/<source>/`) and the response is `unchanged`. A `409` means a newer check happened in the meantime — go back to step 2 with the newer report.
5. **Confirm:** a later `GET /api/graph/drift/{source}` (remembered or re-checked) reports `unchanged`. Remember that reports are in memory and baselines are on disk: after a restart the first read checks again, and an accept before any check in that process is `409`.

Accepting is a statement about the snapshot, never about the database: the database already is what it is, and drift detection only records that a person has reviewed and agreed to it.

### Telling a UI which schema it is reading (`X-Schema-Version`)

Every successful data API response (`/api/data/**`) carries the `X-Schema-Version` header: the **first 12 characters of the same snapshot fingerprint** the drift work uses, computed once per discovery and cached with the schema. `GET /api/data/{source}` also repeats the value as a `schemaVersion` field on every entity descriptor, and a source whose listing is empty carries the version in the header only.

- The version changes exactly when the structure changes, and stays put while the cache serves the same schema. A UI can cache it with the metadata it fetched and refetch as soon as the header differs — that is the whole drift signal a browser needs, and it is the only one available to it, because the drift endpoints are admin endpoints and get no CORS headers.
- Correlating the two views is arithmetic: `currentFingerprint` truncated to 12 characters is the version the data API serves for that snapshot. In the example above the drift check reports `03b3fe9c31a1`, and `X-Schema-Version` moves from `ba6644c4dc21` to `03b3fe9c31a1` for the same reason.
- The version moves as soon as the cache starts serving the new structure — including when a drift check (a `?check=true` read or a scheduled one) forces rediscovery — so it can change **before** anyone accepts the drift. Accepting a baseline does not itself move any version: the fingerprint already matched the schema.

**Security:** the drift endpoints are admin endpoints, exactly like `/api/graph/**` and `/api/schema/**`. A report names every configured source and its structure, and an accept rewrites the baseline files, so they receive no CORS mapping and have no authentication of their own; they are registered by `graph-enabled`, which is off by default for that reason. Keep them off, or behind authentication and authorization, outside local development — see Known Issues #9, #11 and #12. Nothing in drift detection writes to a source database; the files it writes are the snapshots under `snapshot-dir` and, for a confirmed rename, the overlay.

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
