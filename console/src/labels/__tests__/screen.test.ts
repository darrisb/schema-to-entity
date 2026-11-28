import test from "node:test";
import assert from "node:assert/strict";
import { installDom } from "../../test-support/dom.js";
import { ApiError, type ApiClient } from "../../api/client.js";
import type { GraphColumn, GraphEntity, SchemaGraph } from "../../api/types.js";
import { startLabels } from "../screen.js";
import type { DropdownOption } from "../../links/model.js";

installDom();

// Component classes extend HTMLElement at import time: registered only after
// installDom(), exactly as in links/screen.test.ts.
const { ComponentRegistry } = await import("@visual-ui/core");
const { registerNativeComponents } = await import("@visual-ui/components");
registerNativeComponents(new ComponentRegistry());

/** ui-dropdown and ui-input keep their value on the element; tests read it there. */
interface ValueHandle extends HTMLElement {
  value: string;
}

/** Recorded interactions with the fake client. */
interface FakeState {
  graphRequests: boolean[];
  sets: Array<{ id: string; label: string; description: string | null }>;
  removals: string[];
}

/**
 * A fake ApiClient: getGraph replays the queued graphs (the last one repeats
 * forever, an empty queue always fails), setLabel and removeLabel record their
 * calls and may be given an implementation that throws. The other methods
 * throw when called — this screen must not touch them.
 */
function fakeClient(
  graphs: SchemaGraph[],
  behaviour: {
    set?: (id: string) => Promise<void>;
    remove?: (id: string) => Promise<void>;
    /** Makes every getGraph after the first fail, as a post-write reload would. */
    failReload?: boolean;
  } = {},
): { client: ApiClient; state: FakeState } {
  const state: FakeState = { graphRequests: [], sets: [], removals: [] };
  const fail = async (): Promise<never> => {
    throw new Error("method not expected by this screen");
  };
  const client: ApiClient = {
    getGraph: async (includeRejected: boolean): Promise<SchemaGraph> => {
      state.graphRequests.push(includeRejected);
      if (behaviour.failReload && state.graphRequests.length > 1) {
        throw new ApiError(500, "the document could not be re-read");
      }
      const graph = graphs[Math.min(state.graphRequests.length - 1, graphs.length - 1)];
      if (graph === undefined) {
        throw new ApiError(500, "the graph could not be built");
      }
      return graph;
    },
    setLabel: async (id, label, description) => {
      state.sets.push({ id, label, description });
      await behaviour.set?.(id);
    },
    removeLabel: async (id) => {
      state.removals.push(id);
      await behaviour.remove?.(id);
    },
    decideRelationship: fail,
    addManualRelationship: fail,
    removeRelationship: fail,
    listEntities: fail,
    queryEntity: fail,
    getDrift: fail,
    acceptDrift: fail,
    confirmRename: fail,
  };
  return { client, state };
}

function column(
  id: string,
  name: string,
  label: string | null = null,
  description: string | null = null,
): GraphColumn {
  return { id, name, type: "varchar(255)", category: "TEXT", nullable: false, label, description };
}

function entity(
  id: string,
  source: string,
  name: string,
  columns: GraphColumn[],
  label: string | null = null,
  description: string | null = null,
): GraphEntity {
  return {
    id,
    source,
    name,
    kind: "table",
    label,
    description,
    keyColumns: ["id"],
    columns,
    baseTables: [],
  };
}

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

/** The starting document: a labelled entity, one labelled column, one bare one. */
const USERS = entity("demo:users", "demo", "users", [
  column("demo:users.id", "id"),
  column("demo:users.username", "username", "User name"),
], "Users", "Registered accounts");
const INVOICES = entity("billing:invoices", "billing", "invoices", [
  column("billing:invoices.id", "id"),
]);

function usersLabeled(label: string, description: string): GraphEntity {
  return entity("demo:users", "demo", "users", [
    column("demo:users.id", "id"),
    column("demo:users.username", "username", "User name"),
  ], label, description);
}

/** Mounts the screen in a fresh attached container and awaits the first load. */
async function mount(client: ApiClient): Promise<{ container: HTMLElement; done: () => Promise<void> }> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const done = startLabels(container, client);
  await done();
  return { container, done };
}

function entityDropdown(container: HTMLElement): ValueHandle {
  const found = container.querySelector<ValueHandle>("ui-dropdown[data-dropdown='entity']");
  assert.ok(found, "the entity dropdown is on screen");
  return found;
}

/**
 * Picks an option the way a user does: set the inner <select> and fire its
 * change, which the component turns into its own bubbling change event.
 */
function choose(dropdown: ValueHandle, value: string): void {
  const select = dropdown.shadowRoot?.querySelector<HTMLSelectElement>("select");
  assert.ok(select, "ui-dropdown has rendered its select");
  select.value = value;
  select.dispatchEvent(new Event("change"));
}

/** The ids the entity dropdown currently offers. */
function optionValues(dropdown: ValueHandle): string[] {
  const parsed: unknown = JSON.parse(dropdown.getAttribute("options") ?? "[]");
  assert.ok(Array.isArray(parsed), "options is a JSON array");
  return (parsed as DropdownOption[]).map((option) => option.value);
}

function rowCard(container: HTMLElement, id: string): HTMLElement | null {
  return container.querySelector(`ui-card[data-label-id="${id}"]`);
}

function inputIn(card: HTMLElement, field: "label" | "description"): ValueHandle {
  const found = card.querySelector<ValueHandle>(`ui-input[data-field="${field}"]`);
  assert.ok(found, `the row has a ${field} input`);
  return found;
}

/** Types the way a user does: change the inner input, which the component re-dispatches from its host. */
function typeInto(input: ValueHandle, value: string): void {
  const inner = input.shadowRoot?.querySelector<HTMLInputElement>("input");
  assert.ok(inner, "ui-input has rendered its inner input");
  inner.value = value;
  inner.dispatchEvent(new Event("input"));
}

function buttonIn(parent: Element, action: string): HTMLElement | null {
  return parent.querySelector<HTMLElement>(`ui-button[data-action="${action}"]`);
}

function errorArea(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>(".screen-error:not(.row-error)");
}

function rowError(card: HTMLElement): HTMLElement | null {
  return card.querySelector<HTMLElement>(".row-error");
}

/** Selects the fixture entity so its rows are on screen. */
async function pickUsers(container: HTMLElement): Promise<void> {
  choose(entityDropdown(container), "demo:users");
}

test("the screen loads, fills the entity dropdown and shows only a hint before a pick", async () => {
  const fake = fakeClient([graphWith([INVOICES, USERS])]);
  const { container } = await mount(fake.client);

  assert.deepEqual(fake.state.graphRequests, [true]);
  assert.deepEqual(optionValues(entityDropdown(container)), ["billing:invoices", "demo:users"]);
  assert.equal(container.querySelector("ui-card[data-label-id]"), null, "no rows yet");
  assert.match(
    container.querySelector(".labels-rows ui-text")?.getAttribute("content") ?? "",
    /Choose an entity/,
  );
});

test("a load failure shows the ApiError message and offers no options", async () => {
  const fake = fakeClient([]);
  const { container } = await mount(fake.client);

  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.textContent, "the graph could not be built");
  assert.equal(area.hidden, false);
  assert.deepEqual(optionValues(entityDropdown(container)), []);
});

test("picking an entity renders the entity row then one per column, prefilled from the document", async () => {
  const fake = fakeClient([graphWith([INVOICES, USERS])]);
  const { container } = await mount(fake.client);
  await pickUsers(container);

  const ids = [...container.querySelectorAll("ui-card[data-label-id]")].map((card) =>
    card.getAttribute("data-label-id"),
  );
  assert.deepEqual(ids, ["demo:users", "demo:users.id", "demo:users.username"]);

  const entityRow = rowCard(container, "demo:users");
  assert.ok(entityRow);
  assert.equal(entityRow.getAttribute("title"), "Entity · demo · users");
  assert.equal(inputIn(entityRow, "label").value, "Users");
  assert.equal(inputIn(entityRow, "description").value, "Registered accounts");

  const bareRow = rowCard(container, "demo:users.id");
  assert.ok(bareRow);
  assert.equal(inputIn(bareRow, "label").value, "", "an unlabelled column starts blank");
  assert.equal(inputIn(bareRow, "description").value, "");

  const usernameRow = rowCard(container, "demo:users.username");
  assert.ok(usernameRow);
  assert.equal(inputIn(usernameRow, "label").value, "User name");

  // Every row: Save disabled until edited, Clear ready.
  for (const row of [entityRow, bareRow, usernameRow]) {
    assert.ok(buttonIn(row, "save")?.hasAttribute("disabled"), "Save starts disabled");
    assert.equal(buttonIn(row, "clear")?.hasAttribute("disabled"), false, "Clear is ready");
  }
});

test("Save is disabled until a field changes, then calls setLabel and reloads with the pick kept", async () => {
  // The reload returns the document as it looks after the save.
  const fake = fakeClient([
    graphWith([INVOICES, USERS]),
    graphWith([INVOICES, usersLabeled("People", "Registered accounts")]),
  ]);
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  const save = buttonIn(row, "save");
  assert.ok(save);
  save.click();
  assert.deepEqual(fake.state.sets, [], "an untouched row submits nothing");

  typeInto(inputIn(row, "label"), "People");
  assert.equal(save.hasAttribute("disabled"), false, "a changed field frees Save");

  save.click();
  await done();

  assert.deepEqual(fake.state.sets, [
    { id: "demo:users", label: "People", description: "Registered accounts" },
  ]);
  assert.equal(fake.state.graphRequests.length, 2, "the document was re-read after the save");
  // The reload re-rendered: the row shows the saved value and Save is locked again.
  const freshRow = rowCard(container, "demo:users");
  assert.ok(freshRow);
  assert.equal(inputIn(freshRow, "label").value, "People");
  assert.ok(buttonIn(freshRow, "save")?.hasAttribute("disabled"), "Save is disabled after the reload");
  assert.equal(entityDropdown(container).value, "demo:users", "the picked entity stayed selected");
});

test("reverting a field disables Save again", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  const save = buttonIn(row, "save");
  assert.ok(save);
  const labelInput = inputIn(row, "label");

  typeInto(labelInput, "People");
  assert.equal(save.hasAttribute("disabled"), false);
  typeInto(labelInput, "Users");
  assert.equal(save.hasAttribute("disabled"), true, "back to the document value: nothing to save");
});

test("saving a column row calls setLabel with the column id and sends a blank description as null", async () => {
  const fake = fakeClient([graphWith([USERS])]);
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users.username");
  assert.ok(row);
  typeInto(inputIn(row, "label"), "Display name");
  buttonIn(row, "save")?.click();
  await done();

  assert.deepEqual(fake.state.sets, [
    { id: "demo:users.username", label: "Display name", description: null },
  ]);
});

test("Clear calls removeLabel with the row id and reloads with the pick kept", async () => {
  // The reload returns the document with the entity label gone.
  const fake = fakeClient([
    graphWith([USERS]),
    graphWith([usersLabeled("", "") , USERS]),
  ]);
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  buttonIn(row, "clear")?.click();
  await done();

  assert.deepEqual(fake.state.removals, ["demo:users"]);
  assert.equal(fake.state.graphRequests.length, 2, "the screen re-read the document");
  const freshRow = rowCard(container, "demo:users");
  assert.ok(freshRow);
  assert.equal(inputIn(freshRow, "label").value, "", "the cleared label reads back blank");
  assert.equal(entityDropdown(container).value, "demo:users");
});

test("a failed Save shows the server's message next to that row only and does not reload", async () => {
  const fake = fakeClient([graphWith([USERS])], {
    set: async (id) => {
      if (id === "demo:users.username") {
        throw new ApiError(400, "label must not be blank");
      }
    },
  });
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users.username");
  assert.ok(row);
  typeInto(inputIn(row, "label"), "   ");
  buttonIn(row, "save")?.click();
  await done();

  const error = rowError(row);
  assert.ok(error);
  assert.equal(error.hidden, false);
  assert.equal(error.textContent, "label must not be blank");
  // Only the failed row reports; the others stay quiet, and the screen banner too.
  const otherRow = rowCard(container, "demo:users");
  assert.ok(otherRow);
  assert.equal(rowError(otherRow)?.hidden, true);
  assert.equal(errorArea(container)?.hidden, true);
  assert.equal(fake.state.graphRequests.length, 1, "a failed save does not reload");
  // The typed value stays and the buttons free up for a retry.
  assert.equal(inputIn(row, "label").value, "   ");
  assert.equal(buttonIn(row, "save")?.hasAttribute("disabled"), false);
  assert.equal(buttonIn(row, "clear")?.hasAttribute("disabled"), false);
});

test("a failed Clear shows the message on its row and frees the buttons", async () => {
  const fake = fakeClient([graphWith([USERS])], {
    remove: async () => {
      throw new ApiError(500, "the overlay file could not be written");
    },
  });
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  const clear = buttonIn(row, "clear");
  assert.ok(clear);
  clear.click();
  await done();

  const error = rowError(row);
  assert.ok(error);
  assert.equal(error.textContent, "the overlay file could not be written");
  assert.equal(fake.state.graphRequests.length, 1, "a failed clear does not reload");
  assert.equal(clear.hasAttribute("disabled"), false, "the row stays usable");
});

test("a save that succeeds but whose reload fails frees the row instead of leaving it locked", async () => {
  const fake = fakeClient([graphWith([USERS])], { failReload: true });
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  typeInto(inputIn(row, "label"), "People");
  buttonIn(row, "save")?.click();
  await done();

  assert.deepEqual(fake.state.sets, [
    { id: "demo:users", label: "People", description: "Registered accounts" },
  ]);
  assert.equal(fake.state.graphRequests.length, 2, "the save still asked for a reload");
  // The reload failed into the screen banner, and the card it left in place
  // is usable again: dirty rule free, buttons unlocked.
  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.hidden, false);
  assert.equal(area.textContent, "the document could not be re-read");
  assert.equal(row.isConnected, true, "the failed reload kept the rows as typed");
  assert.equal(inputIn(row, "label").value, "People");
  assert.equal(buttonIn(row, "save")?.hasAttribute("disabled"), false, "Save is freed for a retry");
  assert.equal(buttonIn(row, "clear")?.hasAttribute("disabled"), false, "Clear is freed too");
});

test("a clear that succeeds but whose reload fails frees the row and keeps Save on the dirty rule", async () => {
  const fake = fakeClient([graphWith([USERS])], { failReload: true });
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  buttonIn(row, "clear")?.click();
  await done();

  assert.deepEqual(fake.state.removals, ["demo:users"]);
  assert.equal(fake.state.graphRequests.length, 2, "the clear still asked for a reload");
  assert.equal(errorArea(container)?.hidden, false, "the reload failure shows in the screen banner");
  // Nothing is typed, so the dirty rule keeps Save locked while Clear frees up.
  assert.equal(buttonIn(row, "save")?.hasAttribute("disabled"), true);
  assert.equal(buttonIn(row, "clear")?.hasAttribute("disabled"), false);
});

test("a reload that finds the picked entity gone clears the selection and the rows", async () => {
  const fake = fakeClient([
    graphWith([USERS]),
    graphWith([INVOICES]),
  ]);
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  buttonIn(rowCard(container, "demo:users") ?? container, "clear")?.click();
  await done();

  // ui-dropdown restores its value even into an options list that no longer
  // carries it, so the stale pick must be cleared explicitly.
  const dropdown = entityDropdown(container);
  assert.equal(dropdown.value, "");
  const select = dropdown.shadowRoot?.querySelector("select");
  assert.ok(select);
  assert.equal(select.value, "", "the entity select shows no stale id");
  assert.equal(container.querySelector("ui-card[data-label-id]"), null, "the rows are gone");
});

test("a second Save click while one is in flight sends exactly one request", async () => {
  let release: () => void = () => {};
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  const fake = fakeClient([graphWith([USERS])], { set: () => pending });
  const { container, done } = await mount(fake.client);
  await pickUsers(container);

  const row = rowCard(container, "demo:users");
  assert.ok(row);
  typeInto(inputIn(row, "description"), "All the accounts");
  const save = buttonIn(row, "save");
  assert.ok(save);
  save.click();
  assert.ok(save.hasAttribute("disabled"), "Save is disabled while the request runs");
  save.click();
  assert.equal(fake.state.sets.length, 1);

  release();
  await done();

  assert.equal(fake.state.sets.length, 1, "one request for two clicks");
  assert.equal(fake.state.graphRequests.length, 2, "one reload for the one request");
});
