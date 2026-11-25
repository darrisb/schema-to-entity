# Known Issues

Findings from building JSON metadata from the live database schema and verifying that
real entities can be created from it.

## Verified working

The generated JSON was fed to the real consumer (the deleted
`spring-example` `DynamicEntityMappingConfig.SpringMetadataHbmReader`, reconstructed
verbatim from commit `0678801`) and run against the live Postgres container with
`ddl-auto=validate`:

- `SessionFactory` builds; all 4 entities validate against the real DDL.
- All 4 entities `SELECT` real rows.
- `get()` works by `Long` id, by `String` id (`DynamicSession`), and by composite id
  (`DynamicPostTag`).
- `INSERT` works and the `bigserial` identity generator assigns the id, confirming
  `"generator": "identity"` is correct for the driver's `bigserial` reporting.
- `UPDATE` and `DELETE` both work.
- Types round-trip correctly: `jsonb` to String, `bpchar(64)`, `numeric(12,2)` to
  BigDecimal, `timestamp` to Date, `bigserial` to Long.

All issues below are therefore *not* blocking entity creation from the JSON.

## Issues

### 1. One-to-many collection navigation throws ConcurrentModificationException

**Impact:** Navigating an inverse collection, e.g. `DynamicUser.posts`, fails at runtime.
Scalar properties, ids, and many-to-one associations are unaffected.

**Cause:** The consumer's HBM writer emits `<set><key>` for a one-to-many. hbm.xml
requires `<key-many-to-one>` there. The failure surfaces as recursion inside Hibernate's
dynamic-map collection handling:

```
org.hibernate.collection.spi.PersistentSet.injectLoadedState
  -> PersistentSet.hashCode
  -> ...CollectionLoaderSingleKey.load
  -> finishLoadingCollections
  -> injectLoadedState   (ConcurrentModificationException)
```

**Not a JSON defect.** The JSON correctly records that `DynamicUser` has a one-to-many
`posts` targeting `DynamicPost` keyed on `author_id`. The HBM writer is what is wrong.

**Status:** Unfixable in this repo right now. The consumer was deleted and no Hibernate
dependency exists in `api` or `demo`. Fix it when the entity-creation path is restored.

### 2. A table with no primary key cannot become an entity

**Impact:** Hibernate requires an identifier, so PK-less tables have no valid entity.

**Handling:** The transformer emits `"primaryKey": {"synthetic": true}`
(`api/src/main/java/com/example/dynamicmeta/SpringMetadataTransformer.java:108`) and the
consumer rejects it with a clear message. This was a bug: the transformer previously
emitted `"primaryKey": null`, which the consumer would have rendered as `<id name="" .../>`
— silently invalid mappings instead of a diagnosable failure. Fixed, with a test.

### 3. Nothing in the repo consumes the generated JSON

**Impact:** The JSON is produced but unused. There is no code path that turns it into
entities, and no CRUD API. Entity creation was only verifiable by reconstructing the
deleted consumer from git history.

**Status:** Open. Restoring the HBM writer plus a `POST /api/entities`-style API would
close this and make issue 1 fixable.

### 4. Postgres `text` columns surface as `text(2147483647)`

**Impact:** Cosmetic and confusing in the API response and the UI's raw JSON panel.

**Cause:** `SchemaDiscoveryService.displayType()`
(`api/src/main/java/com/example/dynamicmeta/SchemaDiscoveryService.java:170`) appends
`COLUMN_SIZE` for any column the driver reports as `VARCHAR`. Postgres reports `text`
columns that way, with a size of 2^31-1.

**Not a mapping defect:** the transformer discards the bogus length, and metadata
generated from the live schema is byte-identical to metadata generated from the sample
schema. Offered to suppress the length for unbounded types; not done.

### 5. `db/init/*.sql` only runs on an empty Postgres data directory

**Impact:** Editing `db/init/01-schema.sql` has no effect on an existing `postgres_data`
volume. Postgres logs `Skipping initialization` and the schema silently stays stale.
This caused an empty schema that looked like a discovery bug.

**Workaround:** `docker compose down -v` then `docker compose up -d`. This drops the
volume and all its data. Consider an explicit migration step if the schema needs to
change repeatedly.

### 6. Security: unauthenticated arbitrary database connections

**Impact:** `POST /api/schema/extract-postgres` accepts a caller-supplied connection
string with credentials and opens it. It is reachable through the UI's nginx proxy with no
authentication. `schema-to-entity.explicit-connections-enabled` disables it and now
defaults to `false` (FX-008), but `demo/src/main/resources/application.yml` still sets it
to `true` for local development, so the demo endpoint remains exposed when unauthenticated.

**Mitigations in place:**

| Control | Setting / Mechanism | Default |
|---|---|---|
| Toggle | `schema-to-entity.explicit-connections-enabled` | `false` (demo sets `true` for local development; keep off or behind auth in production) |
| Host allowlist | `schema-to-entity.allowed-hosts` (exact + `*` wildcard patterns) | `[]` (any host) |
| URL parameter allowlist | Hard-coded in `ConnectionPolicy` (JDBC-specific params only) | — |
| Embedded DB rule | `schema-to-entity.allow-embedded-databases` blocks H2 `mem:`/`file:` etc. | `false` |
| Concurrency limit | `schema-to-entity.max-concurrent-explicit-connections` | 4 |
| Secret redaction | `ErrorSecretRedactor` on all error responses | always on |
| Timeouts | `connect-timeout` (10 s) / `socket-timeout` (60 s) | — |
| Read-only | Connections opened with read-only flag where the driver supports it | — |

**What remains:** No authentication or authorization. Any caller that can reach the
endpoint can supply credentials and query arbitrary databases (within the policy limits
above). Require authentication and authorization before any non-local deployment.

### 7. Build output was committed to git

**Impact:** `api/target/**` and `ui/ngserve.log` were tracked, so every build produced
spurious diffs. There was no root `.gitignore` (only `ui/.gitignore`).

**Status:** Fixed. Root `.gitignore` added and the 29 tracked files untracked with
`git rm --cached`. **The untracking is staged but not committed** — run `git commit` to
record it.

### 8. Security: the data API has no authentication

**Impact:** `/api/data/**` has no authentication or authorization of its own. Any
caller that can reach the API can list the entities of every configured source and
read their rows. There is no per-source access control: anything configured as a
source is readable by anyone who can reach the API.

**Mitigations in place:**

| Control | Setting / Mechanism | Default |
|---|---|---|
| Read-only | Only `SELECT`/`SELECT COUNT(*)` is generated; pools are read-only | always on |
| CORS | `schema-to-entity.data-allowed-origins` limits browser origins | `[]` (no CORS headers) |
| Schema cache | Per-source discovery is cached and bounded | `10m` |
| Secret redaction | Error messages pass through `SecretRedactor` | always on |

**What remains:** No authentication or authorization. Require authentication before
any non-local deployment and give each source a `SELECT`-only database account. See
the "Database accounts" and "Named data sources" sections of `api/README.md`.

### 9. Security: the graph and overlay endpoints have no authentication

**Impact:** `/api/graph/**` has no authentication or authorization of its own — it is
unauthenticated like the rest of the admin API (`/api/schema/**`, `/api/data/**`).
The combined document names every configured source, its entities, columns and
relationships, and the overlay endpoints accept edits: labels, relationship
decisions and manual relationships. Any caller that can reach the API can read the
document and write overlay edits.

**Mitigations in place:**

| Control | Setting / Mechanism | Default |
|---|---|---|
| Toggle | `schema-to-entity.graph-enabled` | `false` |
| Dependency | Graph beans also require `data-api-enabled: true` | `false` |
| CORS | Graph endpoints receive no CORS mapping — only `/api/data/**` does | always on |
| Read-only sources | Pools are read-only; nothing in the graph path writes to any source database | always on |
| Secret redaction | Error responses pass through the secret redactor; an unavailable source reports a generic error, never a driver message | always on |
| Overlay bounds | Labels ≤ 200 chars, descriptions ≤ 2000, decisions limited to `confirmed`/`rejected` | always on |

**What remains:** No authentication or authorization. Require authentication before
any non-local deployment, exactly as for `/api/schema/extract` (issue 6) and
`/api/data/**` (issue 8).

### 10. The overlay is a local file, not shared between instances

**Impact:** Overlay edits (labels, relationship decisions, manual relationships) are
stored in a single JSON file on the server's local disk,
`schema-to-entity.overlay-file` (default `./schema-overlay.json`). Run more than one
instance of the host application and each keeps its own overlay: an edit made
through one instance does not appear in another, the file is not replicated, and it
is not backed up beyond the file system it sits on.

**Workaround:** Give instances of one deployment the same mounted volume as the
overlay path — they will then share the file, though concurrent writers on different
hosts can still lose an edit, because the store is last-writer-wins per save.

**Status:** Open by design for this stage. A multi-instance deployment that needs
consistent overlays requires a shared store (a database table or object storage)
behind `OverlayStore`; the local file is the simplest store that survives
rediscovery and restarts.

## Caveats on the above

The repo has no Hibernate dependency. All Hibernate findings (issue 1, and the successful
verification) came from a scratch harness using `hibernate-core` 6.6.18.Final against
Postgres 15.19. Treat version-specific Hibernate behaviour as indicative until the real
dependency is pinned in the project.
