# Schema to Entity — Admin Console

A plain TypeScript + Vite single-page app with **no framework** (no Angular,
React or Vue). It renders native Web Components (`ui-button`, `ui-card`,
`ui-text`, `ui-input`, `ui-dropdown`, `ui-table`) from the vendored
`@visual-ui/*` packages in [`vendor/`](vendor/README.md). The app is a hash
router (`src/router.ts`) over five screens, each owning a folder under `src/`
with its logic split so the pure parts are testable without a browser:

- `<screen>/model.ts` — data shaping and validation, no DOM.
- `<screen>/screen.ts` — thin controller: fetch, wire events, build elements.
- `<screen>/__tests__/` — `node:test` suites; DOM tests run under happy-dom
  through `src/test-support/dom.ts`.

It only calls Schema to Entity's own `/api/...` endpoints with relative URLs
(see `src/api/client.ts`); it never talks to a source database.

> **Authentication:** the console has no login and no session handling yet —
> exactly like the rest of the admin API it drives. Everything it can do,
> anyone who can reach it can do. **Do not expose it beyond the local
> machine** until authentication exists: `docker-compose.yml` publishes the
> API on port 8081 of all interfaces, so on anything but a trusted network
> bind it to loopback instead (e.g. `127.0.0.1:8081:8080`).

## Screens

| Route | What it does |
| --- | --- |
| `#/relationships` | Lists proposed relationships grouped by status (from `GET /api/graph`) with Confirm/Reject buttons. Decisions go through `PUT`/`POST /api/graph/overlay/relationships`, then the document reloads. A Refresh button re-fetches (also the escape hatch for a hung request). |
| `#/links` | Form to assert a manual link between two columns (entity and column pickers are `ui-dropdown`s refilled per selection), plus the list of existing manual links with Remove buttons. Validation is local first, so an invalid pair sends no request. |
| `#/labels` | Picks an entity, then shows one card per entity/column with Label and Description inputs and Save/Clear pairs wired to `PUT`/`DELETE /api/graph/overlay/labels`. Save is disabled until a field differs from its stored value; a successful write reloads the document. |
| `#/data` | Read-only browser for the data API. Pick an entity, and a `ui-table` pages and sorts its rows (`GET /api/data/{source}/{entity}`); the table's own page-change and sort-change events drive the queries. The screen never mutates rows. |
| `#/drift` | Shows `GET /api/graph/drift` as one card per source: status, last check, and changes grouped by severity (breaking/review/additive). "Check now" re-reads, "Accept as baseline" (confirm-guarded) and "Confirm rename" act through the drift endpoints, then re-check. |

## Running in Docker

The console is built inside the API image (`Dockerfile`) and served by Spring
Boot from `classpath:/static/console/`, same origin as the API:

```bash
docker-compose up --build
```

Then open **http://localhost:8081/console/**.

## Running in development

The Vite dev server runs outside Docker and proxies `/api` so no CORS setup
is needed:

```bash
npm --prefix console install   # installs the vendored @visual-ui tarballs
npm --prefix console run dev   # or: cd console && npm run dev
```

Open **http://localhost:5174/console/** (the app is served under its base
path `/console/`, not the root). The proxy targets `http://localhost:8081`
by default; set `CONSOLE_API_URL` to point at another API instance:

```bash
CONSOLE_API_URL=http://localhost:9090 npm --prefix console run dev
```

Other scripts (all via `npm --prefix console run <name>` or inside `console/`):

- `build` — type-checks (`tsc --noEmit`) and emits the bundle to `dist/`.
- `preview` — serves the built bundle through Vite.
- `test` — see below.

Requires Node >= 22.12 (see `engines` in `package.json`).

## Tests

```bash
npm --prefix console test
```

This compiles `src/` with `tsconfig.test.json` into `dist-test/` and runs
`node --test "dist-test/**/*.test.js"`. Tests use `node:test` and
`node:assert`; tests that need a DOM use happy-dom via
`src/test-support/dom.ts`. Test files live in `__tests__` folders next to the
code they cover.

## Upgrading the vendored UI library

The four `@visual-ui/*` packages come from the native-ui-library repository
(`visual-ui-builder`) as tarballs pinned in `vendor/` and installed with
`file:vendor/<name>.tgz` dependencies, so builds work offline and inside
Docker without a registry. To upgrade (also documented in
[`vendor/README.md`](vendor/README.md)):

1. In the library repo, run `npm run pack:packages`.
2. Copy the four tarballs from its `release/` folder into `console/vendor/`.
3. Update the file names in `console/package.json` if the version changed.
4. Run `npm install` in `console/` (refreshes `package-lock.json`), then
   `npm test` and `npm run build` here to verify.

The currently vendored version is **0.2.0**.
