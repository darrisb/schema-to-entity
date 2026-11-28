import test from "node:test";
import assert from "node:assert/strict";
import { installDom } from "../../test-support/dom.js";
import { ApiError, type ApiClient } from "../../api/client.js";
import type { GraphRelationship, RelationshipStatus, SchemaGraph } from "../../api/types.js";
import { startRelationships } from "../screen.js";

installDom();

// Component classes extend HTMLElement at import time: registered only after
// installDom(), exactly as in router.test.ts.
const { ComponentRegistry } = await import("@visual-ui/core");
const { registerNativeComponents } = await import("@visual-ui/components");
registerNativeComponents(new ComponentRegistry());

const PROPOSED_ID = "billing:invoices.user_id->demo:users.id";

/** Recorded interactions with the fake client. */
interface FakeState {
  graphRequests: boolean[];
  decisions: Array<{ id: string; status: RelationshipStatus | null }>;
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
 * forever, an empty queue always fails), the decision methods record their
 * calls and may be given an implementation that throws. A getGraph override
 * takes over the response entirely — requests are still counted. The data
 * and drift methods throw when called — this screen must not touch them.
 */
function fakeClient(
  graphs: SchemaGraph[],
  behaviour: {
    getGraph?: () => Promise<SchemaGraph>;
    decide?: (id: string, status: RelationshipStatus | null) => Promise<void>;
    remove?: (id: string) => Promise<void>;
  } = {},
): { client: ApiClient; state: FakeState } {
  const state: FakeState = { graphRequests: [], decisions: [], removals: [] };
  const fail = async (): Promise<never> => {
    throw new Error("method not expected by this screen");
  };
  const client: ApiClient = {
    getGraph: async (includeRejected: boolean): Promise<SchemaGraph> => {
      state.graphRequests.push(includeRejected);
      if (behaviour.getGraph) {
        return behaviour.getGraph();
      }
      const graph = graphs[Math.min(state.graphRequests.length - 1, graphs.length - 1)];
      if (graph === undefined) {
        throw new ApiError(500, "the graph could not be built");
      }
      return graph;
    },
    decideRelationship: async (id, status) => {
      state.decisions.push({ id, status });
      await behaviour.decide?.(id, status);
    },
    removeRelationship: async (id) => {
      state.removals.push(id);
      await behaviour.remove?.(id);
    },
    addManualRelationship: fail,
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

function relationship(id: string, status: RelationshipStatus): GraphRelationship {
  return {
    id,
    from: "billing:invoices.user_id",
    to: "demo:users.id",
    origin: "inferred",
    scope: "cross-source",
    status,
    confidence: 0.8,
    evidence: ["name-match"],
  };
}

function graphWith(relationships: GraphRelationship[]): SchemaGraph {
  return {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities: [],
    relationships,
    orphans: [],
  };
}

/** Mounts the screen in a fresh attached container and awaits the first load. */
async function mount(client: ApiClient): Promise<{ container: HTMLElement; done: () => Promise<void> }> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const done = startRelationships(container, client);
  await done();
  return { container, done };
}

function cardOf(container: HTMLElement, id: string): HTMLElement | null {
  return container.querySelector(`ui-card[data-relationship-id="${id}"]`);
}

function buttonIn(parent: Element | null, action: string): HTMLElement | null {
  return parent?.querySelector<HTMLElement>(`ui-button[data-action="${action}"]`) ?? null;
}

function sectionOf(container: HTMLElement, key: string): Element | null {
  return container.querySelector(`[data-section="${key}"]`);
}

function errorArea(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>(".screen-error");
}

/** The relationship ids of the cards in one section, in render order. */
function sectionCardIds(container: HTMLElement, key: string): string[] {
  return Array.from(sectionOf(container, key)?.querySelectorAll("ui-card") ?? []).map(
    (card) => card.getAttribute("data-relationship-id") ?? "",
  );
}

test("the screen loads the document with includeRejected=true and renders it", async () => {
  const fake = fakeClient([graphWith([relationship(PROPOSED_ID, "proposed")])]);
  const { container } = await mount(fake.client);

  assert.deepEqual(fake.state.graphRequests, [true]);
  assert.equal(sectionOf(container, "proposed")?.querySelectorAll("ui-card").length, 1);
  // The loading line is gone and no error is shown once data is in.
  assert.equal(container.querySelector('ui-text[content="Loading the schema document…"]'), null);
  assert.equal(errorArea(container)?.textContent, "");
  assert.equal(errorArea(container)?.hidden, true);
});

test("a load failure shows the ApiError message above the list", async () => {
  const fake = fakeClient([]);
  const { container } = await mount(fake.client);

  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.textContent, "the graph could not be built");
  assert.equal(area.hidden, false);
  // The error area sits directly above the list of sections.
  assert.equal(area.nextElementSibling?.className, "relationships-list");
});

test("clicking Confirm decides 'confirmed' then reloads and re-renders", async () => {
  const id = PROPOSED_ID;
  const fake = fakeClient([
    graphWith([relationship(id, "proposed")]),
    graphWith([relationship(id, "confirmed")]),
  ]);
  const { container, done } = await mount(fake.client);

  const card = cardOf(container, id);
  assert.ok(card);
  const confirm = buttonIn(card, "confirm");
  assert.ok(confirm);

  confirm.click();
  // Synchronously, before any await, the card's buttons are disabled.
  assert.ok(confirm.hasAttribute("disabled"));
  assert.ok(buttonIn(card, "reject")?.hasAttribute("disabled"));

  await done();

  assert.deepEqual(fake.state.decisions, [{ id, status: "confirmed" }]);
  assert.equal(fake.state.graphRequests.length, 2, "the graph was re-read after the decision");
  // Re-rendered from the fresh document: the card sits in Confirmed, with
  // Undo enabled (the reload built new buttons).
  assert.equal(sectionOf(container, "confirmed")?.querySelectorAll("ui-card").length, 1);
  const undo = buttonIn(cardOf(container, id), "undo");
  assert.ok(undo, "the reloaded card offers Undo");
  assert.equal(undo.hasAttribute("disabled"), false);
});

test("clicking Reject decides 'rejected' then reloads and re-renders", async () => {
  const id = PROPOSED_ID;
  const fake = fakeClient([
    graphWith([relationship(id, "proposed")]),
    graphWith([relationship(id, "rejected")]),
  ]);
  const { container, done } = await mount(fake.client);

  const card = cardOf(container, id);
  assert.ok(card);
  const reject = buttonIn(card, "reject");
  assert.ok(reject);

  reject.click();
  assert.ok(reject.hasAttribute("disabled"), "the card is disabled while the decision runs");

  await done();

  assert.deepEqual(fake.state.decisions, [{ id, status: "rejected" }]);
  assert.equal(fake.state.graphRequests.length, 2, "the graph was re-read after the decision");
  assert.equal(sectionOf(container, "proposed")?.querySelectorAll("ui-card").length, 0);
  assert.deepEqual(sectionCardIds(container, "rejected"), [id]);
});

test("a second click while a decision is in flight sends exactly one request", async () => {
  const id = PROPOSED_ID;
  const decision = deferred<void>();
  const fake = fakeClient(
    [graphWith([relationship(id, "proposed")]), graphWith([relationship(id, "confirmed")])],
    { decide: () => decision.promise },
  );
  const { container, done } = await mount(fake.client);

  const card = cardOf(container, id);
  assert.ok(card);
  const confirm = buttonIn(card, "confirm");
  const reject = buttonIn(card, "reject");
  assert.ok(confirm && reject);

  confirm.click();
  // Both clicks reach the host while the decision is pending; the disabled
  // guard must swallow the re-entry, and the sibling button too.
  confirm.click();
  reject.click();
  assert.deepEqual(fake.state.decisions, [{ id, status: "confirmed" }]);
  assert.equal(fake.state.graphRequests.length, 1);

  decision.resolve(undefined);
  await done();

  assert.deepEqual(fake.state.decisions, [{ id, status: "confirmed" }]);
  assert.equal(fake.state.graphRequests.length, 2, "one reload for the single decision");
  assert.deepEqual(sectionCardIds(container, "confirmed"), [id]);
});

test("Undo clears a decision with status null; Remove calls removeRelationship", async () => {
  const confirmedId = "a:b.c->d:e.f";
  const manualId = "demo:users.id->billing:invoices.customer_id";
  const manual = { ...relationship(manualId, "confirmed"), origin: "manual" as const };
  const fake = fakeClient([graphWith([relationship(confirmedId, "confirmed"), manual])]);
  const { container, done } = await mount(fake.client);

  const undo = buttonIn(cardOf(container, confirmedId), "undo");
  assert.ok(undo);
  undo.click();
  await done();

  const remove = buttonIn(cardOf(container, manualId), "remove");
  assert.ok(remove, "the manual card also carries Remove");
  remove.click();
  await done();

  assert.deepEqual(fake.state.decisions, [{ id: confirmedId, status: null }]);
  assert.deepEqual(fake.state.removals, [manualId]);
});

test("a failed decision shows the error and re-enables the buttons; success then clears it", async () => {
  const id = PROPOSED_ID;
  let failing = true;
  const fake = fakeClient(
    [graphWith([relationship(id, "proposed")]), graphWith([relationship(id, "confirmed")])],
    {
      decide: async () => {
        if (failing) {
          throw new ApiError(409, "relationship changed, reload before deciding");
        }
      },
    },
  );
  const { container, done } = await mount(fake.client);

  const card = cardOf(container, id);
  assert.ok(card);
  buttonIn(card, "confirm")?.click();
  await done();

  const area = errorArea(container);
  assert.ok(area);
  assert.equal(area.textContent, "relationship changed, reload before deciding");
  assert.equal(area.hidden, false);
  // No reload on failure — the same card stays with its buttons clickable.
  assert.equal(fake.state.graphRequests.length, 1);
  const confirm = buttonIn(card, "confirm");
  assert.ok(confirm);
  assert.equal(confirm.hasAttribute("disabled"), false);

  failing = false;
  confirm.click();
  await done();

  assert.equal(area.textContent, "");
  assert.equal(area.hidden, true);
  assert.equal(fake.state.graphRequests.length, 2, "the success reloaded the document");
  assert.equal(sectionOf(container, "confirmed")?.querySelectorAll("ui-card").length, 1);
});

test("the Refresh button re-reads the document with in-flight feedback", async () => {
  const id = PROPOSED_ID;
  const fake = fakeClient([graphWith([relationship(id, "proposed")]), graphWith([])]);
  const { container, done } = await mount(fake.client);

  const header = container.querySelector("ui-card");
  assert.ok(header);
  assert.equal(header.getAttribute("title"), "Relationships");
  const refresh = buttonIn(header, "refresh");
  assert.ok(refresh, "the header card carries the Refresh button");

  refresh.click();
  // While the reload is in flight the loading line is back and Refresh is
  // disabled, so the user sees the request and a second click is ignored.
  assert.ok(header.querySelector('ui-text[content="Loading the schema document…"]'));
  assert.ok(refresh.hasAttribute("disabled"));
  refresh.click();

  await done();

  assert.equal(fake.state.graphRequests.length, 2, "one click, one reload");
  assert.equal(cardOf(container, id), null, "the refreshed (empty) document was re-rendered");
  assert.equal(header.querySelector('ui-text[content="Loading the schema document…"]'), null);
  assert.equal(refresh.hasAttribute("disabled"), false);
});

test("a stale load response cannot overwrite a newer render", async () => {
  const idA = "a:a.a->b:b.b";
  const idB = "c:c.c->d:d.d";
  const bothProposed = graphWith([relationship(idA, "proposed"), relationship(idB, "proposed")]);
  const bConfirmed = graphWith([relationship(idA, "proposed"), relationship(idB, "confirmed")]);
  const stale = deferred<SchemaGraph>();
  let graphCalls = 0;
  const fake = fakeClient([], {
    getGraph: () => {
      graphCalls += 1;
      // A's reload (call 2) hangs until after B's newer reload answered.
      if (graphCalls === 2) {
        return stale.promise;
      }
      return Promise.resolve(graphCalls === 3 ? bConfirmed : bothProposed);
    },
  });
  const { container, done } = await mount(fake.client);

  const confirmA = buttonIn(cardOf(container, idA), "confirm");
  const confirmB = buttonIn(cardOf(container, idB), "confirm");
  assert.ok(confirmA && confirmB);
  confirmA.click();
  confirmB.click();
  // pending() tracks the most recent click's work, which completes first.
  await done();
  assert.deepEqual(sectionCardIds(container, "proposed"), [idA], "the fresh document rendered");
  assert.deepEqual(sectionCardIds(container, "confirmed"), [idB]);

  // A's reload now answers with the old document, which still shows B proposed.
  stale.resolve(bothProposed);
  // Let the continuation of that late response run to the end.
  await new Promise((resolve) => {
    setTimeout(resolve, 0);
  });

  assert.deepEqual(
    sectionCardIds(container, "proposed"),
    [idA],
    "the stale response was discarded, not re-rendered",
  );
  assert.deepEqual(sectionCardIds(container, "confirmed"), [idB]);
  const header = container.querySelector("ui-card");
  assert.ok(header);
  assert.equal(header.querySelector('ui-text[content="Loading the schema document…"]'), null);
  assert.equal(buttonIn(header, "refresh")?.hasAttribute("disabled"), false);
});
