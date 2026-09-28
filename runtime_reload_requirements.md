# Runtime Reload and Java Migration Requirements

This document separates two related but different efforts:

- Track 1: changes to the existing `spring-example` application.
- Track 2: future conversion of the Node.js backend into reusable Java modules with pluggable database integrations, plus conversion of the standalone Angular UI into an embeddable Angular feature.

Track 1 should be implemented without requiring Track 2. Track 2 should not force extra behavior into `spring-example` until the Java modules exist.

## Track 1: Spring Example Runtime Reload

### Goal

Allow the existing `spring-example` app to load `spring-entities-config.json` from a runtime-accessible folder and reload dynamic Hibernate entities without restarting the process.

The `spring-example` app should support this flow:

1. A metadata JSON file already exists.
2. The JSON is saved outside the packaged jar, for example `spring-example/config/spring-entities-config.json`.
3. An admin reload endpoint is called.
4. The app validates the file, rebuilds dynamic Hibernate mappings, swaps to the new runtime mapping context, and serves the new entity set.

This track does not require converting the Node.js backend to Java.

### Current Limitation

The current `spring-example` app reads `spring-entities-config.json` from the classpath during startup in `DynamicEntityMappingConfig`.

Hibernate mappings are built into a `SessionFactory` / `EntityManagerFactory`. Once that factory is built, Hibernate does not add or remove entity mappings in-place. Runtime reload therefore requires building a new factory and swapping the application to use it.

### Spring Example Configuration

Add external metadata configuration:

```yaml
dynamic-entities:
  config-path: ./config/spring-entities-config.json
  reload:
    enabled: true
  dialect: org.hibernate.dialect.PostgreSQLDialect
  ddl-auto: validate
```

Requirements:

- Default to the existing classpath resource for backward compatibility.
- Prefer `dynamic-entities.config-path` when present.
- Support absolute and relative file paths.
- Resolve relative paths from the `spring-example` process working directory.
- Keep existing `dynamic-entities.dialect` and `dynamic-entities.ddl-auto` behavior.

### Spring Example Runtime Architecture

Introduce a runtime registry/service that owns the active dynamic entity context.

Suggested component:

```text
DynamicEntityRuntime
```

Responsibilities:

- Read metadata JSON from the configured source.
- Validate metadata before changing the active runtime.
- Build a new `SessionFactory` from metadata.
- Build controller lookup maps from the same metadata.
- Atomically swap the active runtime after successful build.
- Close the old `SessionFactory` only after the new one is active.
- Expose active entity names, id metadata, field types, relationships, and `SessionFactory`.

The current `EntityManagerFactory` bean should not be the fixed source of truth for dynamic operations. Dynamic CRUD should obtain the current Hibernate `SessionFactory` from `DynamicEntityRuntime`.

### Spring Example Reload Endpoint

Add an admin endpoint:

```http
POST /api/entities/reload
```

Successful response:

```json
{
  "status": "reloaded",
  "entityCount": 4,
  "entities": ["DynamicUser", "DynamicPost"]
}
```

Failure response:

```json
{
  "error": "Schema-validation: missing table [users]"
}
```

Requirements:

- Return `200` when reload succeeds.
- Return `400` for invalid metadata JSON.
- Return `422` for metadata that cannot be mapped.
- Return `409` if a reload is already in progress.
- Return `502` or `500` for database validation/startup failures.
- Do not replace the active runtime when reload fails.

### Spring Example Concurrency

Reload must not corrupt in-flight requests.

Minimum acceptable design:

- Store the active runtime in an `AtomicReference`.
- Each request captures the current runtime at the start.
- Reload builds the new runtime independently.
- Swap the reference only after build and validation succeed.
- Close the previous runtime after the swap.

Safer design:

- Use a `ReadWriteLock`.
- CRUD requests use the read lock briefly to capture the active runtime.
- Reload uses the write lock for the final swap.

### Spring Example Service Updates

Update `DynamicEntityService`:

- Stop relying on `@PersistenceContext EntityManager`.
- Use the current `SessionFactory` from `DynamicEntityRuntime`.
- Open a session per operation or use Spring-managed transaction integration around the active factory.
- Keep dynamic entity operations based on `Map<String, Object>`.

Required methods:

```text
findAll(entityName)
findById(entityName, idProperty, parsedId)
persist(entityName, values)
```

Each method must use the active runtime captured for that request.

### Spring Example Controller Updates

Update `DynamicEntityController`:

- Read known entities, id info, property types, and relationships from `DynamicEntityRuntime`.
- Keep current endpoints:
  - `GET /api/entities`
  - `GET /api/entities/{entityName}`
  - `GET /api/entities/{entityName}/{id}`
  - `POST /api/entities/{entityName}`
- Add:
  - `POST /api/entities/reload`
- Keep string id support. Do not parse `java.lang.String` ids as numbers.
- Keep composite-id behavior explicit.

### Spring Example Metadata Validation

Before swapping runtimes, validate:

- Root object has `entities`.
- Each entity has `entityName`, `tableName`, and `primaryKey`.
- Primary key is either single-column or composite.
- Property and id types are known or intentionally passed through.
- Relationship targets exist in the same metadata file.
- Many-to-one relationships have at least one join column.
- One-to-many relationships have `targetEntity`, `mappedBy`, and `foreignKeyColumn`.

Database validation:

- Use Hibernate `ddl-auto=validate` by default.
- If validation fails, reload fails and the old runtime remains active.

### Spring Example File Placement

Recommended runtime location:

```text
spring-example/config/spring-entities-config.json
```

Do not require writing into:

```text
spring-example/src/main/resources
```

Files inside `src/main/resources` are build-time inputs. They are packaged into the jar and should be treated as defaults, not runtime state.

### Spring Example Testing

Add tests for:

- Initial load from classpath fallback.
- Initial load from external file.
- Successful reload adds a new entity.
- Failed reload keeps the old entity set active.
- Invalid JSON returns a client error.
- Missing database table fails reload and keeps old runtime.
- String primary keys remain strings.
- Numeric primary keys parse to the correct numeric type.
- Composite id entities return clear guidance for id lookup.
- Concurrent read during reload does not throw.

Add smoke test steps:

1. Start Postgres.
2. Start `spring-example` with external `dynamic-entities.config-path`.
3. Call `GET /api/entities`.
4. Replace JSON file with a valid changed config.
5. Call `POST /api/entities/reload`.
6. Confirm `GET /api/entities` reflects the new metadata.
7. Replace JSON with invalid metadata.
8. Call reload and confirm the previous metadata is still active.

## Track 2: Node.js to Java and Angular Host Integration

### Goal

Convert the current Node.js backend capabilities into reusable Java modules so the schema-to-entity workflow can be embedded into existing Java and Spring applications.

Convert the current standalone Angular UI into an Angular feature/library that can be integrated into an existing Angular application that already works with a Spring backend.

This track should be designed separately from `spring-example`. The `spring-example` app can later consume the Java modules, but it should not be the module implementation.

### Target Integrated Architecture

Recommended future project structure:

```text
schema-to-entity/
  schema-to-entity-core/
  schema-to-entity-postgres/
  schema-to-entity-spring-boot-starter/
  schema-to-entity-angular/
  schema-to-entity-spring-demo/
```

Module responsibilities:

- `schema-to-entity-core`: shared schema model, metadata model, naming utilities, type mapping contracts, transformer, validation, and JSON serialization.
- `schema-to-entity-postgres`: Postgres-specific JDBC introspection and Postgres type mapping.
- `schema-to-entity-spring-boot-starter`: Spring Boot auto-configuration, runtime metadata loading, dynamic Hibernate mapping, CRUD service, and reload support.
- `schema-to-entity-angular`: Angular routes, components, models, and API client services for integration into a host Angular app.
- `schema-to-entity-spring-demo`: example app showing how to use the starter.

The current Node backend and standalone Angular app can stay during migration, but the long-term integration should not require an external Node service or a separate Angular application.

Target host application shape:

```text
Existing Spring application
  + schema-to-entity-spring-boot-starter
  + schema-to-entity-postgres

Existing Angular application
  + schema-to-entity-angular feature routes/components
```

### Migration Phases

Port the current Node backend responsibilities into Java in phases:

1. Port the schema-to-Spring metadata transformer from `backend/springTransformerService.js` into `schema-to-entity-core`.
2. Add parity tests that feed the same schema JSON into Node and Java and compare generated metadata.
3. Port Postgres introspection from `backend/postgresIntrospectionService.js` into `schema-to-entity-postgres`.
4. Add JDBC integration tests using disposable Postgres.
5. Extract reusable dynamic entity runtime behavior into `schema-to-entity-spring-boot-starter`.
6. Add Spring endpoints matching or intentionally versioning the current Node API contract.
7. Point the current standalone Angular UI at the Spring endpoints instead of Node.
8. Convert the standalone Angular UI into `schema-to-entity-angular`.
9. Integrate the Angular feature into the target host Angular application.
10. Keep a separate Spring demo app as a consumer of the starter.

Required Java APIs:

```java
DatabaseSchema schema = databaseIntrospector.extract(dataSource, options);
SpringEntityMetadata metadata = springMetadataTransformer.transform(schema, transformOptions);
dynamicEntityRuntime.reload(metadata);
```

The transformer and introspection APIs must be usable without Spring. Spring Boot integration should be an adapter on top of the plain Java core.

### API Compatibility During Migration

Keep the API contract stable while moving backend behavior from Node to Spring.

Current Node-style endpoints should either be preserved by the Spring starter or replaced by clear versioned endpoints:

```text
GET  /api/schema/sample
POST /api/schema/extract-postgres
POST /api/schema/to-spring-metadata
```

Preferred Spring endpoint shape:

```text
GET  /api/schema/sample
POST /api/schema/extract
POST /api/schema/to-spring-metadata
POST /api/schema/generate-and-reload
POST /api/entities/reload
GET  /api/entities
GET  /api/entities/{entityName}
GET  /api/entities/{entityName}/{id}
POST /api/entities/{entityName}
```

Requirements:

- Keep request/response payloads compatible where practical so the UI can move from Node to Spring with minimal changes.
- If an endpoint changes, introduce a versioned route instead of silently changing behavior.
- The Angular API client should centralize endpoint paths so host apps can configure them.
- The Java starter should expose backend endpoints only when enabled by configuration.

### Multi-Database Adapter Requirements

Postgres is the first supported integration, but the design must allow other databases such as MySQL, MariaDB, SQL Server, Oracle, H2, and SQLite to be added later.

Define a database adapter SPI in `schema-to-entity-core`:

```java
public interface DatabaseIntrospector {
    String databaseId();
    DatabaseSchema extract(Connection connection, IntrospectionOptions options);
}
```

Recommended supporting contracts:

```java
public interface DatabaseTypeMapper {
    String databaseId();
    JavaTypeMapping map(DatabaseColumn column);
}

public interface DatabaseDialectSupport {
    String databaseId();
    String hibernateDialectClassName();
    boolean supportsIdentityColumns();
    boolean supportsSequences();
}
```

Requirements:

- Each database integration must live in its own module.
- Core must not contain database-specific SQL.
- Core may contain shared models and generic type categories.
- Database adapters must return one normalized `DatabaseSchema` model.
- The transformer must operate on the normalized model, not raw database-specific result sets.
- Adding a new database should not require editing the Spring runtime code.
- Database modules should be discoverable through Java `ServiceLoader` and Spring Boot auto-configuration.

Example modules:

```text
schema-to-entity-postgres
schema-to-entity-mysql
schema-to-entity-sqlserver
schema-to-entity-oracle
```

### Postgres Adapter Requirements

Postgres should be the reference implementation that shows future database authors how to add support.

Move the current Postgres-specific logic into `schema-to-entity-postgres`:

- Connection validation.
- Schema name validation.
- Table discovery.
- Column discovery.
- Primary key discovery.
- Foreign key discovery.
- Postgres type normalization.
- Postgres-specific native SQL type handling such as `jsonb`, `bpchar`, `uuid`, arrays, and identity columns.

Postgres adapter package example:

```text
com.example.schematoentity.postgres
  PostgresDatabaseIntrospector
  PostgresTypeMapper
  PostgresDialectSupport
  PostgresAutoConfiguration
```

Postgres should register itself through:

```text
META-INF/services/...DatabaseIntrospector
META-INF/services/...DatabaseTypeMapper
META-INF/services/...DatabaseDialectSupport
```

Spring Boot users should be able to add Postgres support by installing one dependency:

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>schema-to-entity-postgres</artifactId>
  <version>${schema-to-entity.version}</version>
</dependency>
```

Then configure:

```yaml
schema-to-entity:
  database:
    type: postgres
    schema: public
```

### Adding a New Database Integration

Document this as the expected extension path for future databases:

1. Create a new module named `schema-to-entity-{database}`.
2. Implement `DatabaseIntrospector`.
3. Implement `DatabaseTypeMapper`.
4. Implement `DatabaseDialectSupport`.
5. Register implementations with `ServiceLoader`.
6. Add Spring Boot auto-configuration if needed.
7. Add integration tests with Testcontainers or Docker.
8. Add documentation showing required Maven dependency and YAML configuration.

Each database module must include documentation with:

- Maven dependency.
- Supported database versions.
- Required JDBC driver.
- Example configuration.
- Known type mapping limitations.
- Unsupported features.

Example user setup for a future MySQL adapter:

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>schema-to-entity-mysql</artifactId>
  <version>${schema-to-entity.version}</version>
</dependency>
```

```yaml
schema-to-entity:
  database:
    type: mysql
    schema: app
```

### Normalized Schema Model Requirements

All database adapters must output the same normalized schema model.

Minimum model:

```text
DatabaseSchema
  databaseId
  catalog
  schema
  tables[]

DatabaseTable
  name
  columns[]
  primaryKey
  foreignKeys[]

DatabaseColumn
  name
  rawType
  normalizedType
  nullable
  length
  precision
  scale
  defaultValue
  autoIncrement

DatabasePrimaryKey
  columns[]

DatabaseForeignKey
  name
  columns[]
  referencedTable
  referencedColumns[]
```

Requirements:

- Preserve raw database type information for debugging and native Hibernate SQL type hints.
- Normalize common type categories for cross-database transformation.
- Support composite primary keys.
- Support composite foreign keys in the model, even if the first implementation only maps simple relationships.
- Include database identifier metadata so generated output can choose the right Hibernate dialect/type behavior.

### Future Spring Boot Starter Configuration

When the Java modules exist, the reusable Spring Boot starter should use its own configuration namespace:

```yaml
schema-to-entity:
  metadata:
    location: ./config/spring-entities-config.json
  database:
    type: postgres
    schema: public
  dynamic-entities:
    reload:
      enabled: true
    ddl-auto: validate
```

Requirements:

- Default to classpath metadata for backward compatibility.
- Prefer `schema-to-entity.metadata.location` when present.
- Support absolute and relative file paths.
- Resolve relative paths from the process working directory.
- Infer Hibernate dialect from the selected database adapter unless explicitly overridden.
- Avoid replacing an existing application's primary `EntityManagerFactory` unless explicitly configured to do so.
- Use the host Spring application's primary `DataSource` by default.
- Support selecting a named or secondary `DataSource` for schema generation and dynamic entity runtime.
- Allow endpoint exposure to be enabled or disabled by configuration.
- Do not assume the starter owns the host application's security, CORS, error handling, or global API prefix.

Example host configuration:

```yaml
schema-to-entity:
  enabled: true
  api:
    enabled: true
    base-path: /api/schema-to-entity
  metadata:
    location: ./config/spring-entities-config.json
  database:
    type: postgres
    schema: public
    datasource: primary
  dynamic-entities:
    reload:
      enabled: true
    ddl-auto: validate
```

### Future Generate and Reload Endpoint

When the Java database adapter and transformer are available, the reusable Spring starter may add:

```http
POST /api/schema/generate
POST /api/schema/generate-and-reload
```

`generate-and-reload` should:

1. Use the configured database adapter.
2. Introspect the database.
3. Transform the normalized schema into Spring metadata.
4. Save the metadata file if configured.
5. Reload the dynamic runtime.
6. Return generated entity names and reload status.

### Angular Feature Library Requirements

Convert the current `ui` app into a reusable Angular feature package.

Target package:

```text
schema-to-entity-angular/
  src/
    components/
    services/
    models/
    routes/
```

The Angular package should export:

- A top-level page component.
- Route definitions.
- API client services.
- TypeScript models for schema and metadata payloads.
- Configuration provider for API base URL.

Example host route integration:

```ts
import { schemaToEntityRoutes } from '@your-org/schema-to-entity-angular';

export const routes: Routes = [
  {
    path: 'schema-to-entity',
    children: schemaToEntityRoutes
  }
];
```

Example standalone component integration:

```ts
{
  path: 'schema-to-entity',
  loadComponent: () =>
    import('@your-org/schema-to-entity-angular')
      .then(m => m.SchemaToEntityPageComponent)
}
```

Requirements:

- The feature must not bootstrap its own Angular application.
- The feature must not assume ownership of global routing.
- The feature must not hard-code `/api` as the only backend base path.
- The feature must use a configurable API base URL.
- The feature should work with the host app's existing authentication and HTTP interceptors.
- The feature should avoid global styles that can leak into the host Angular app.
- The feature should expose components/services with stable public APIs.
- The feature should avoid forcing a specific design system unless provided by the host.

Suggested Angular configuration API:

```ts
provideSchemaToEntity({
  apiBaseUrl: '/api/schema-to-entity'
})
```

### Backend/UI Transition

During migration, the existing Node backend and UI may remain as development tools. Long term, the Java modules should provide the same generation capabilities.

Optional transitional updates:

- Node backend export can write the generated JSON to the configured runtime folder.
- UI can add a button that calls the `spring-example` reload endpoint after export.
- UI should show reload success/failure separately from JSON generation success.
- Java backend/starter should eventually expose equivalent generate/save/reload operations so Node is no longer required.
- Once Spring endpoint parity exists, switch the Angular UI from Node endpoints to Spring endpoints.
- Once the UI is stable against Spring endpoints, convert it from a standalone Angular app into `schema-to-entity-angular`.
- Integrate `schema-to-entity-angular` into the target host Angular application.

Potential Node endpoint during transition:

```http
POST /api/schema/save-spring-metadata
```

Payload:

```json
{
  "metadata": {},
  "path": "../spring-example/config/spring-entities-config.json"
}
```

This endpoint should protect against path traversal if exposed beyond local development.

### Java Conversion Testing

Add tests for:

- Java transformer output matches the existing Node transformer for sample schemas.
- Postgres Java adapter output matches the existing Node Postgres introspection shape.
- Database adapter discovery finds the Postgres adapter.
- Missing database adapter fails with a clear error.
- Adding a second database adapter does not require changes to core or Spring runtime code.
- Spring endpoints match the expected UI API contract.
- Angular API client works against the Spring starter endpoints.
- Angular feature can be mounted under a non-root route in a host Angular app.
- Angular feature works with a custom API base path.

Add adapter smoke test steps:

1. Start Postgres.
2. Configure `schema-to-entity.database.type=postgres`.
3. Run database introspection.
4. Generate metadata.
5. Reload a dynamic runtime from generated metadata.
6. Confirm dynamic REST endpoints can query the generated entities.

## Shared Security Requirements

Reload and generate-and-reload are admin operations.

Before production use:

- Require authentication.
- Require authorization for reload and generation.
- Log who triggered reload.
- Do not accept arbitrary file paths from unauthenticated users.
- Avoid returning secrets from database connection failures.
- Avoid exposing database connection strings in API responses or logs.

## Shared Operational Notes

- Reload may briefly increase memory usage because old and new `SessionFactory` instances coexist.
- Reload should be treated as a controlled admin operation, not a high-frequency workflow.
- Schema changes still need to happen before reload when `ddl-auto=validate`.
- If automatic database migration is needed later, add a migration step before rebuilding the runtime.
- Database adapters should be versioned independently when possible because database-specific behavior changes at a different pace than core transformation logic.
