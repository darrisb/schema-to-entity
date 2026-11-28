import test from "node:test";
import assert from "node:assert/strict";
import { installDom } from "../../test-support/dom.js";
import { ApiError, type ApiClient, type QueryOptions } from "../../api/client.js";
import type { DataPage, GraphColumn, GraphEntity, SchemaGraph } from "../../api/types.js";
import type { DropdownOption } from "../../links/model.js";
import { startData } from "../screen.js";
import { PAGE_SIZE } from "../model.js";

installDom();

// Component classes extend HTMLElement at import time: registered only after
// installDom(), exactly as in labels/screen.test.ts.
const { ComponentRegistry } = await import("@visual-ui/core");
const { registerNativeComponents } = await import("@visual-ui/components");
registerNativeComponents(new ComponentRegistry());

/** ui-dropdown keeps its value on the element; tests read it there. */
interface ValueHandle extends HTMLElement {
  value: string;
}

/** One queryEntity interaction as the screen made it. */
interface QueryCall {
  source: string;
  entity: string;
  options: QueryOptions | undefined;
}

/**
 * A fake ApiClient: getGraph replays the queued graphs (an empty queue always
 * fails, as the other screens' fakes do); queryEntity records every call and
 * returns behaviour.query (default: a full 50-row page of 340 for the
 * requested offset) or throws behaviour.queryError. The other methods throw
 * when called — this screen must not touch them.
 */
function fakeClient(
  graphs: SchemaGraph[],
  behaviour: {
    query?: (options: QueryOptions) => DataPage;
    queryError?: unknown;
  } = {},
): { client: ApiClient; queries: QueryCall[] } {
  const queries: QueryCall[] = [];
  let graphLoads = 0;
  const fail = async (): Promise<never> => {
    throw new Error("method not expected by this screen");
  };
  const client: ApiClient = {
    getGraph: async (): Promise<SchemaGraph> => {
      graphLoads += 1;
      const graph = graphs[Math.min(graphLoads - 1, graphs.length - 1)];
      if (graph === undefined) {
        throw new ApiError(500, "the graph could not be built");
      }
      return graph;
    },
    queryEntity: async (source, entity, options): Promise<DataPage> => {
      queries.push({ source, entity, options });
      if (behaviour.queryError !== undefined) {
        throw behaviour.queryError;
      }
      if (behaviour.query !== undefined) {
        return behaviour.query(options ?? {});
      }
      const offset = options?.offset ?? 0;
      return { items: rows(offset, PAGE_SIZE), total: 340, limit: PAGE_SIZE, offset };
    },
    decideRelationship: fail,
    addManualRelationship: fail,
    removeRelationship: fail,
    setLabel: fail,
    removeLabel: fail,
    listEntities: fail,
    getDrift: fail,
    acceptDrift: fail,
    confirmRename: fail,
  };
  return { client, queries };
}

/** `count` rows for a page starting at `offset`, with 1-based ids. */
function rows(offset: number, count: number): Record<string, unknown>[] {
  const items: Record<string, unknown>[] = [];
  for (let index = 0; index < count; index += 1) {
    items.push({ id: offset + index + 1, username: `user${String(offset + index + 1)}` });
  }
  return items;
}

function column(name: string, label: string | null = null): GraphColumn {
  return {
    id: `demo:users.${name}`,
    name,
    type: "varchar(80)",
    category: "TEXT",
    nullable: false,
    label,
    description: null,
  };
}

const USERS: GraphEntity = {
  id: "demo:users",
  source: "demo",
  name: "users",
  kind: "table",
  label: null,
  description: null,
  keyColumns: ["id"],
  columns: [column("id", "Id"), column("username")],
  baseTables: [],
};

const INVOICES: GraphEntity = {
  ...USERS,
  id: "billing:invoices",
  source: "billing",
  name: "invoices",
  columns: [column("id")],
};

function graphWith(entities: GraphEntity[]): SchemaGraph {
  return {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities,
    relationships: [],
    orphans: [],
  };
}

/** Mounts the screen in a fresh attached container and awaits the document load. */
async function mount(client: ApiClient): Promise<{ container: HTMLElement; done: () => Promise<void> }> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const done = startData(container, client);
  await done();
  return { container, done };
}

function entityDropdown(container: HTMLElement): ValueHandle {
  const found = container.querySelector<ValueHandle>("ui-dropdown[data-dropdown='entity']");
  assert.ok(found, "the entity dropdown is on screen");
  return found;
}

/** Picks an option the way a user does: change the inner select, let the component re-dispatch. */
function choose(dropdown: ValueHandle, value: string): void {
  const select = dropdown.shadowRoot?.querySelector<HTMLSelectElement>("select");
  assert.ok(select, "ui-dropdown has rendered its select");
  select.value = value;
  select.dispatchEvent(new Event("change"));
}

function tableOnScreen(container: HTMLElement): HTMLElement {
  const found = container.querySelector<HTMLElement>("ui-table[data-table='rows']");
  assert.ok(found, "a ui-table is on screen");
  return found;
}

function pageText(container: HTMLElement): string {
  return container.querySelector("ui-text[data-info='page']")?.getAttribute("content") ?? "";
}

function optionValues(dropdown: ValueHandle): string[] {
  const parsed: unknown = JSON.parse(dropdown.getAttribute("options") ?? "[]");
  assert.ok(Array.isArray(parsed), "options is a JSON array");
  return (parsed as DropdownOption[]).map((option) => option.value);
}

/** Clicks a header button inside the table's shadow, driving the component's real sort-change. */
function clickHeader(table: HTMLElement, field: "id" | "username"): void {
  const buttons = [...(table.shadowRoot?.querySelectorAll('[part="header-button"]') ?? [])];
  const button = buttons[field === "id" ? 0 : 1];
  assert.ok(button, `the ${field} header button is rendered`);
  (button as HTMLElement).click();
}

/** Clicks the table's own Next button, driving the component's real page-change. */
function clickNext(table: HTMLElement): void {
  const next = table.shadowRoot?.querySelector<HTMLButtonElement>('[part="next"]');
  assert.ok(next, "the Next button is rendered");
  assert.equal(next.disabled, false, "Next is enabled while a full page is bound");
  next.click();
}

test("the screen loads the document, fills the entity dropdown and shows a hint", async () => {
  const fake = fakeClient([graphWith([INVOICES, USERS])]);
  const { container } = await mount(fake.client);

  assert.deepEqual(optionValues(entityDropdown(container)), ["billing:invoices", "demo:users"]);
  assert.equal(container.querySelector("ui-table"), null, "no table before a pick");
  assert.match(
    container.querySelector(".data-results ui-text:not([data-info='page'])")?.getAttribute("content") ?? "",
    /Choose an entity/,
  );
  assert.deepEqual(fake.queries, [], "nothing is queried before a pick");
});

test("a load failure shows the ApiError message in the screen banner", async () => {
  const fake = fakeClient([]);
  const { container } = await mount(fake.client);

  const area = container.querySelector<HTMLElement>(".screen-error");
  assert.ok(area);
  assert.equal(area.hidden, false);
  assert.equal(area.textContent, "the graph could not be built");
});

test("selecting an entity requests limit 50 offset 0 and writes columns, rows and page text", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  assert.deepEqual(fake.queries, [
    { source: "demo", entity: "users", options: { limit: 50, offset: 0 } },
  ]);

  const table = tableOnScreen(container);
  assert.equal(
    table.getAttribute("columns"),
    JSON.stringify([
      { field: "id", label: "Id" },
      { field: "username", label: "username" },
    ]),
  );
  assert.equal(table.getAttribute("page-size"), "50");
  const bound: unknown = JSON.parse(table.getAttribute("data-bound-rows") ?? "null");
  assert.ok(Array.isArray(bound) && bound.length === 50, "a full page is bound");
  assert.deepEqual((bound as Record<string, unknown>[])[0], { id: 1, username: "user1" });
  assert.equal(table.getAttribute("data-ds-status"), "idle");
  assert.equal(pageText(container), "Rows 1\u201350 of 340");
});

test("nested row values are bound as JSON text", async () => {
  const fake = fakeClient([graphWith([USERS])], {
    query: () => ({
      items: [{ id: 1, tags: ["admin", "ops"], meta: { level: 3 }, note: null }],
      total: 1,
      limit: PAGE_SIZE,
      offset: 0,
    }),
  });
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  const bound: unknown = JSON.parse(tableOnScreen(container).getAttribute("data-bound-rows") ?? "null");
  assert.deepEqual(bound, [
    { id: 1, tags: '["admin","ops"]', meta: '{"level":3}', note: null },
  ]);
  assert.equal(pageText(container), "Rows 1\u20131 of 1");
});

test("an empty page sets data-ds-status=empty and reads No rows", async () => {
  const fake = fakeClient([graphWith([USERS])], {
    query: () => ({ items: [], total: 0, limit: PAGE_SIZE, offset: 0 }),
  });
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  const table = tableOnScreen(container);
  assert.equal(table.getAttribute("data-ds-status"), "empty");
  assert.equal(pageText(container), "No rows");
});

test("a failing query sets data-ds-status=error with the server's message", async () => {
  const fake = fakeClient([graphWith([USERS])], {
    queryError: new ApiError(502, "Database query failed"),
  });
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  const table = tableOnScreen(container);
  assert.equal(table.getAttribute("data-ds-status"), "error");
  assert.equal(table.getAttribute("data-ds-error"), "Database query failed");
  assert.equal(pageText(container), "");
  assert.equal(
    container.querySelector<HTMLElement>(".screen-error")?.hidden,
    true,
    "the row failure stays on the table, not the banner",
  );
});

test("the table's Next button requests the event's offset", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  clickNext(tableOnScreen(container));
  await done();

  assert.deepEqual(fake.queries.map((call) => call.options), [
    { limit: 50, offset: 0 },
    { limit: 50, offset: 50 },
  ]);
  assert.equal(pageText(container), "Rows 51\u2013100 of 340");
});

test("a header sort reloads from offset 0 and the following page keeps the sort", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  clickNext(tableOnScreen(container)); // offset 50 first, to prove the sort resets it
  await done();
  clickHeader(tableOnScreen(container), "username");
  await done();

  assert.deepEqual(fake.queries.map((call) => call.options), [
    { limit: 50, offset: 0 },
    { limit: 50, offset: 50 },
    { limit: 50, offset: 0, sort: "username" },
  ]);

  clickNext(tableOnScreen(container));
  await done();
  assert.deepEqual(fake.queries[3].options, { limit: 50, offset: 50, sort: "username" });
});

test("picking another entity starts over on a fresh table", async () => {
  const fake = fakeClient([graphWith([INVOICES, USERS])]);
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();
  clickNext(tableOnScreen(container));
  await done();

  choose(entityDropdown(container), "billing:invoices");
  await done();

  assert.deepEqual(fake.queries.map((call) => [call.source, call.entity, call.options]), [
    ["demo", "users", { limit: 50, offset: 0 }],
    ["demo", "users", { limit: 50, offset: 50 }],
    ["billing", "invoices", { limit: 50, offset: 0 }],
  ]);
  assert.equal(container.querySelectorAll("ui-table").length, 1, "the old table is gone");
});

function refreshButton(container: HTMLElement): HTMLElement {
  const found = container.querySelector<HTMLElement>("ui-button[data-action='refresh']");
  assert.ok(found, "the header card carries the Refresh button");
  return found;
}

test("Refresh re-queries the visible table at its recorded page and sort", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();
  clickHeader(tableOnScreen(container), "username");
  await done();
  clickNext(tableOnScreen(container));
  await done();

  fake.queries.length = 0;
  refreshButton(container).click();
  await done();

  // The same page the table shows (offset 50, username asc), not page one.
  assert.deepEqual(fake.queries, [
    { source: "demo", entity: "users", options: { limit: 50, offset: 50, sort: "username" } },
  ]);
  const table = tableOnScreen(container);
  assert.equal(table.getAttribute("data-ds-status"), "idle");
  assert.equal(pageText(container), "Rows 51\u2013100 of 340");
});

test("Refresh with no entity picked reloads the document only", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container, done } = await mount(fake.client);

  refreshButton(container).click();
  await done();

  assert.deepEqual(fake.queries, [], "no table, so no row query");
  assert.equal(container.querySelector("ui-table"), null);
  assert.deepEqual(optionValues(entityDropdown(container)), ["demo:users"]);
});

test("Refresh that drops the picked entity clears the table and skips the re-query", async () => {
  // Second getGraph replays the graph without demo:users, as if it vanished.
  const fake = fakeClient([graphWith([USERS]), graphWith([INVOICES])]);
  const { container, done } = await mount(fake.client);
  choose(entityDropdown(container), "demo:users");
  await done();

  fake.queries.length = 0;
  refreshButton(container).click();
  await done();

  assert.deepEqual(fake.queries, [], "the dropped entity is not re-queried");
  assert.equal(container.querySelector("ui-table"), null, "its table is off screen");
  assert.equal(entityDropdown(container).value, "");
});
