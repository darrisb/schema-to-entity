import test from "node:test";
import assert from "node:assert/strict";
import { installDom } from "../../test-support/dom.js";
import { ApiError, type ApiClient } from "../../api/client.js";
import type {
  GraphColumn,
  GraphEntity,
  GraphRelationship,
  SchemaGraph,
} from "../../api/types.js";
import { startLinks } from "../screen.js";
import type { DropdownOption } from "../model.js";

installDom();

// Component classes extend HTMLElement at import time: registered only after
// installDom(), exactly as in relationships/screen.test.ts.
const { ComponentRegistry } = await import("@visual-ui/core");
const { registerNativeComponents } = await import("@visual-ui/components");
registerNativeComponents(new ComponentRegistry());

/** ui-dropdown keeps its selection on the element; that is how tests read it. */
interface DropdownHandle extends HTMLElement {
  value: string;
}

/** Recorded interactions with the fake client. */
interface FakeState {
  graphRequests: boolean[];
  adds: Array<{ from: string; to: string }>;
  removals: string[];
}

/** A promise plus its resolver, to hold a request open between assertions. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve: (value: T) => void = () => {};
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

/**
 * A fake ApiClient: getGraph replays the queued graphs (the last one repeats
 * forever, an empty queue always fails), add and remove record their calls and
 * may be given an implementation that throws. The data and drift methods throw
 * when called — this screen must not touch them.
 */
function fakeClient(
  graphs: SchemaGraph[],
  behaviour: {
    add?: (from: string, to: string) => Promise<{ id: string }>;
    remove?: (id: string) => Promise<void>;
  } = {},
): { client: ApiClient; state: FakeState } {
  const state: FakeState = { graphRequests: [], adds: [], removals: [] };
  const fail = async (): Promise<never> => {
    throw new Error("method not expected by this screen");
  };
  const client: ApiClient = {
    getGraph: async (includeRejected: boolean): Promise<SchemaGraph> => {
      state.graphRequests.push(includeRejected);
      const graph = graphs[Math.min(state.graphRequests.length - 1, graphs.length - 1)];
      if (graph === undefined) {
        throw new ApiError(500, "the graph could not be built");
      }
      return graph;
    },
    addManualRelationship: async (from, to) => {
      state.adds.push({ from, to });
      return behaviour.add ? behaviour.add(from, to) : { id: `${from}->${to}` };
    },
    removeRelationship: async (id) => {
      state.removals.push(id);
      await behaviour.remove?.(id);
    },
    decideRelationship: fail,
    setLabel: fail,
    removeLabel: fail,
    listEntities: fail,
    queryEntity: fail,
    getDrift: fail,
    acceptDrift: fail,
    confirmRename: fail,
  };
  return { client, state };
}

function column(id: string, name: string, label: string | null = null): GraphColumn {
  return { id, name, type: "bigint", category: "NUMBER", nullable: false, label, description: null };
}

function entity(
  id: string,
  source: string,
  name: string,
  columns: GraphColumn[],
  label: string | null = null,
): GraphEntity {
  return {
    id,
    source,
    name,
    kind: "table",
    label,
    description: null,
    keyColumns: ["id"],
    columns,
    baseTables: [],
  };
}

function manual(from: string, to: string): GraphRelationship {
  return {
    id: `${from}->${to}`,
    from,
    to,
    origin: "manual",
    scope: from.split(":")[0] === to.split(":")[0] ? "within-source" : "cross-source",
    status: "confirmed",
    confidence: null,
    evidence: [],
  };
}

const ENTITIES: GraphEntity[] = [
  entity("billing:invoices", "billing", "invoices", [
    column("billing:invoices.id", "id"),
    column("billing:invoices.customer_id", "customer_id"),
  ]),
  entity("demo:users", "demo", "users", [
    column("demo:users.id", "id"),
    column("demo:users.username", "username"),
  ]),
];

function graphWith(
  relationships: GraphRelationship[],
  entities: GraphEntity[] = ENTITIES,
): SchemaGraph {
  return {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities,
    relationships,
    orphans: [],
  };
}

const EXISTING = manual("demo:users.id", "billing:invoices.customer_id");
/** The link the create tests submit: users.username → invoices.id. */
const NEW_ID = "demo:users.username->billing:invoices.id";

/** Mounts the screen in a fresh attached container and awaits the first load. */
async function mount(client: ApiClient): Promise<{ container: HTMLElement; done: () => Promise<void> }> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const done = startLinks(container, client);
  await done();
  return { container, done };
}

function dropdown(container: HTMLElement, name: string): DropdownHandle {
  const found = container.querySelector<DropdownHandle>(`ui-dropdown[data-dropdown="${name}"]`);
  assert.ok(found, `the ${name} dropdown is on screen`);
  return found;
}

/** The ids the dropdown currently offers, in option-attribute order. */
function optionValues(dropdown: DropdownHandle): string[] {
  const parsed: unknown = JSON.parse(dropdown.getAttribute("options") ?? "[]");
  assert.ok(Array.isArray(parsed), "options is a JSON array");
  return (parsed as DropdownOption[]).map((option) => option.value);
}

/**
 * Picks an option the way a user does: set the inner <select> and fire its
 * change, which the component turns into its own bubbling change event.
 */
function choose(dropdown: DropdownHandle, value: string): void {
  const select = dropdown.shadowRoot?.querySelector<HTMLSelectElement>("select");
  assert.ok(select, "ui-dropdown has rendered its select");
  select.value = value;
  select.dispatchEvent(new Event("change"));
}

function buttonIn(parent: Element | null, action: string): HTMLElement | null {
  return parent?.querySelector<HTMLElement>(`ui-button[data-action="${action}"]`) ?? null;
}

function cardOf(container: HTMLElement, id: string): HTMLElement | null {
  return container.querySelector(`ui-card[data-relationship-id="${id}"]`);
}

function errorArea(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>(".screen-error:not(.link-form-error)");
}

function formError(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>(".link-form-error");
}

/** Selects a full side of the link: entity, then one of its columns. */
function pickSide(container: HTMLElement, side: "from" | "to", entityId: string, columnId: string): void {
  choose(dropdown(container, `${side}-entity`), entityId);
  choose(dropdown(container, `${side}-column`), columnId);
}

test("the screen loads the document, fills both entity dropdowns and lists manual links", async () => {
  const inferred: GraphRelationship = { ...manual("billing:invoices.id", "demo:users.id"), origin: "inferred" };
  const fake = fakeClient([graphWith([EXISTING, inferred])]);
  const { container } = await mount(fake.client);

  assert.deepEqual(fake.state.graphRequests, [true]);
  const fromEntity = dropdown(container, "from-entity");
  const toEntity = dropdown(container, "to-entity");
  assert.deepEqual(optionValues(fromEntity), ["billing:invoices", "demo:users"]);
  assert.deepEqual(optionValues(toEntity), ["billing:invoices", "demo:users"]);
  // Column dropdowns wait for an entity choice.
  assert.deepEqual(optionValues(dropdown(container, "from-column")), []);
  assert.deepEqual(optionValues(dropdown(container, "to-column")), []);
  // Only the manual relationship is listed; the inferred one is not.
  assert.ok(cardOf(container, EXISTING.id));
  assert.equal(cardOf(container, "billing:invoices.id->demo:users.id"), null);
  // The listed card carries the readable summary line and a Remove button.
  assert.equal(
    cardOf(container, EXISTING.id)?.getAttribute("title"),
    "demo · users.id → billing · invoices.customer_id",
  );
  assert.ok(buttonIn(cardOf(container, EXISTING.id), "remove"));
});

test("a load failure shows the ApiError message and offers no options", async () => {
  const fake = fakeClient([]);
  const { container } = await mount(fake.client);

  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.textContent, "the graph could not be built");
  assert.equal(area.hidden, false);
  assert.deepEqual(optionValues(dropdown(container, "from-entity")), []);
});

test("choosing an entity fills its column dropdown and clears the chosen column", async () => {
  const fake = fakeClient([graphWith([EXISTING])]);
  const { container } = await mount(fake.client);

  const fromEntity = dropdown(container, "from-entity");
  const fromColumn = dropdown(container, "from-column");

  choose(fromEntity, "demo:users");
  assert.deepEqual(optionValues(fromColumn), ["demo:users.id", "demo:users.username"]);

  choose(fromColumn, "demo:users.username");
  assert.equal(fromColumn.value, "demo:users.username");

  // The other side is untouched by this side's choices.
  assert.deepEqual(optionValues(dropdown(container, "to-column")), []);

  // Switching entities drops the column picked from the old one.
  choose(fromEntity, "billing:invoices");
  assert.equal(fromColumn.value, "");
  assert.deepEqual(optionValues(fromColumn), ["billing:invoices.id", "billing:invoices.customer_id"]);
});

test("Create calls addManualRelationship with both column ids, then reloads and resets the form", async () => {
  const created = manual("demo:users.username", "billing:invoices.id");
  const fake = fakeClient([graphWith([EXISTING]), graphWith([EXISTING, created])]);
  const { container, done } = await mount(fake.client);

  pickSide(container, "from", "demo:users", "demo:users.username");
  pickSide(container, "to", "billing:invoices", "billing:invoices.id");

  const create = buttonIn(container, "create");
  assert.ok(create);
  create.click();
  await done();

  assert.deepEqual(fake.state.adds, [{ from: "demo:users.username", to: "billing:invoices.id" }]);
  assert.equal(fake.state.graphRequests.length, 2, "the document was re-read after the link");
  // The reload rendered: the new link is listed next to the old one.
  assert.ok(cardOf(container, NEW_ID), "the created link shows in the list");
  // The form came back empty: no side chosen, column lists reset.
  for (const side of ["from", "to"] as const) {
    assert.equal(dropdown(container, `${side}-entity`).value, "");
    assert.equal(dropdown(container, `${side}-column`).value, "");
    assert.deepEqual(optionValues(dropdown(container, `${side}-column`)), []);
  }
  assert.equal(formError(container)?.hidden, true);
  assert.equal(create.hasAttribute("disabled"), false);
});

test("a same-column link is refused with a message, without any request", async () => {
  const fake = fakeClient([graphWith([])]);
  const { container, done } = await mount(fake.client);

  pickSide(container, "from", "demo:users", "demo:users.id");
  pickSide(container, "to", "demo:users", "demo:users.id");

  buttonIn(container, "create")?.click();
  await done();

  assert.deepEqual(fake.state.adds, [], "nothing was submitted");
  assert.deepEqual(fake.state.graphRequests, [true], "the form was not even reloaded");
  const message = formError(container);
  assert.ok(message);
  assert.equal(message.hidden, false);
  assert.match(message.textContent ?? "", /same column/i);
  // The server-error area stays empty: this is the screen's own validation.
  assert.equal(errorArea(container)?.hidden, true);
});

test("a missing side is refused too, and picking again clears the message", async () => {
  const fake = fakeClient([graphWith([])]);
  const { container } = await mount(fake.client);

  pickSide(container, "from", "demo:users", "demo:users.id");
  buttonIn(container, "create")?.click();

  const message = formError(container);
  assert.ok(message);
  assert.equal(message.hidden, false);
  assert.match(message.textContent ?? "", /both sides/i);
  assert.deepEqual(fake.state.adds, []);

  // Choosing the other side makes the message go away.
  pickSide(container, "to", "billing:invoices", "billing:invoices.id");
  assert.equal(message.hidden, true);
  assert.equal(message.textContent, "");
});

test("a failed Create shows the server's message, keeps the form and re-enables the button", async () => {
  const fake = fakeClient([graphWith([])], {
    add: async () => {
      throw new ApiError(404, "column demo:users.username not found in the document");
    },
  });
  const { container, done } = await mount(fake.client);

  pickSide(container, "from", "demo:users", "demo:users.username");
  pickSide(container, "to", "demo:users", "demo:users.id");
  buttonIn(container, "create")?.click();
  await done();

  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.textContent, "column demo:users.username not found in the document");
  assert.equal(area.hidden, false);
  assert.equal(fake.state.graphRequests.length, 1, "a failed create does not reload");
  // The choices stay so the user can fix and retry.
  assert.equal(dropdown(container, "from-column").value, "demo:users.username");
  const create = buttonIn(container, "create");
  assert.ok(create);
  assert.equal(create.hasAttribute("disabled"), false);
});

test("a second Create click while one is in flight sends exactly one request", async () => {
  const pending = deferred<{ id: string }>();
  const fake = fakeClient([graphWith([])], { add: () => pending.promise });
  const { container, done } = await mount(fake.client);

  pickSide(container, "from", "demo:users", "demo:users.id");
  pickSide(container, "to", "billing:invoices", "billing:invoices.customer_id");

  const create = buttonIn(container, "create");
  assert.ok(create);
  create.click();
  assert.ok(create.hasAttribute("disabled"), "the button is disabled while the link is created");
  create.click();
  assert.deepEqual(fake.state.adds.length, 1);

  pending.resolve({ id: NEW_ID });
  await done();

  assert.deepEqual(fake.state.adds.length, 1, "one request for two clicks");
  assert.equal(fake.state.graphRequests.length, 2, "one reload for the one request");
  assert.equal(create.hasAttribute("disabled"), false);
});

test("Remove calls removeRelationship then reloads, keeping the current form choices", async () => {
  const fake = fakeClient([graphWith([EXISTING]), graphWith([])]);
  const { container, done } = await mount(fake.client);

  // Fill part of the form to check Remove does not wipe it.
  choose(dropdown(container, "from-entity"), "demo:users");

  const card = cardOf(container, EXISTING.id);
  assert.ok(card);
  buttonIn(card, "remove")?.click();
  await done();

  assert.deepEqual(fake.state.removals, [EXISTING.id]);
  assert.equal(fake.state.graphRequests.length, 2, "the list came from the reload");
  assert.equal(cardOf(container, EXISTING.id), null, "the removed link is gone");
  assert.equal(
    container.querySelector("ui-text")?.getAttribute("content"),
    "No manual links yet — create one above.",
  );
  // The reload re-filled the entity dropdowns and kept this side's choice.
  assert.equal(dropdown(container, "from-entity").value, "demo:users");
});

test("a failed Remove shows the server's message and frees the button", async () => {
  const fake = fakeClient([graphWith([EXISTING])], {
    remove: async () => {
      throw new ApiError(404, "the overlay holds no manual relationship for that id");
    },
  });
  const { container, done } = await mount(fake.client);

  const card = cardOf(container, EXISTING.id);
  assert.ok(card);
  const remove = buttonIn(card, "remove");
  assert.ok(remove);
  remove.click();
  await done();

  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.textContent, "the overlay holds no manual relationship for that id");
  assert.equal(fake.state.graphRequests.length, 1, "a failed remove does not reload");
  assert.equal(remove.hasAttribute("disabled"), false, "the still-listed card is clickable again");
});

test("the Refresh button re-reads the document", async () => {
  const fake = fakeClient([graphWith([EXISTING]), graphWith([])]);
  const { container, done } = await mount(fake.client);

  const header = container.querySelector("ui-card");
  assert.ok(header);
  assert.equal(header.getAttribute("title"), "Links");
  const refresh = buttonIn(header, "refresh");
  assert.ok(refresh, "the header card carries the Refresh button");

  refresh.click();
  await done();

  assert.equal(fake.state.graphRequests.length, 2);
  assert.equal(cardOf(container, EXISTING.id), null, "the latest document was re-rendered");
});

test("a reload rebuilds filled sides' column lists and drops vanished columns", async () => {
  // Drifted document: demo:users lost its username column, billing:invoices
  // gained total but kept customer_id.
  const fresh = graphWith([], [
    entity("demo:users", "demo", "users", [column("demo:users.id", "id")]),
    entity("billing:invoices", "billing", "invoices", [
      column("billing:invoices.id", "id"),
      column("billing:invoices.customer_id", "customer_id"),
      column("billing:invoices.total", "total"),
    ]),
  ]);
  const fake = fakeClient([graphWith([]), fresh]);
  const { container, done } = await mount(fake.client);

  pickSide(container, "from", "demo:users", "demo:users.username");
  pickSide(container, "to", "billing:invoices", "billing:invoices.customer_id");

  const refresh = buttonIn(container, "refresh");
  assert.ok(refresh);
  refresh.click();
  await done();

  // The from side's column is gone from the document: list rebuilt, choice dropped.
  const fromColumn = dropdown(container, "from-column");
  assert.deepEqual(optionValues(fromColumn), ["demo:users.id"]);
  assert.equal(fromColumn.value, "");
  // The to side's column still exists: list rebuilt, choice kept.
  const toColumn = dropdown(container, "to-column");
  assert.deepEqual(optionValues(toColumn), [
    "billing:invoices.id",
    "billing:invoices.customer_id",
    "billing:invoices.total",
  ]);
  assert.equal(toColumn.value, "billing:invoices.customer_id");
});
