import test from "node:test";
import assert from "node:assert/strict";
import { installDom } from "../../test-support/dom.js";
import type {
  GraphRelationship,
  RelationshipOrigin,
  RelationshipStatus,
  SchemaGraph,
} from "../../api/types.js";
import type { RelationshipGroups } from "../model.js";
import {
  renderRelationships,
  setRelationshipButtonsEnabled,
  type RelationshipHandlers,
} from "../view.js";

installDom();

// The component classes extend HTMLElement when their modules evaluate, so
// they are imported and registered only after the DOM globals exist — the
// same order main.ts and router.test.ts use.
const { ComponentRegistry } = await import("@visual-ui/core");
const { registerNativeComponents } = await import("@visual-ui/components");
registerNativeComponents(new ComponentRegistry());

const PROPOSED_ID = "billing:invoices.user_id->demo:users.id";
const DECLARED_ID = "demo:posts.author_id->demo:users.id";

function relationship(
  id: string,
  status: RelationshipStatus,
  origin: RelationshipOrigin = "inferred",
  confidence: number | null = null,
  evidence: string[] = [],
): GraphRelationship {
  const [from, to] = id.split("->");
  assert.ok(from !== undefined && to !== undefined, `${id} is from->to`);
  return {
    id,
    from,
    to,
    origin,
    scope: "within-source",
    status,
    confidence,
    evidence,
  };
}

function emptyGroups(groups: Partial<RelationshipGroups>): RelationshipGroups {
  return { proposed: [], confirmed: [], declared: [], rejected: [], ...groups };
}

function emptyGraph(): SchemaGraph {
  return {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities: [],
    relationships: [],
    orphans: [],
  };
}

/** Handlers that record "action:id" for every call. */
function recorder(): { handlers: RelationshipHandlers; calls: string[] } {
  const calls: string[] = [];
  const record = (action: string) => (id: string): void => {
    calls.push(`${action}:${id}`);
  };
  return {
    calls,
    handlers: {
      onConfirm: record("confirm"),
      onReject: record("reject"),
      onUndo: record("undo"),
      onRemove: record("remove"),
    },
  };
}

function render(groups: RelationshipGroups): HTMLElement {
  const container = document.createElement("div");
  // Attaching to the document upgrades the ui-* elements as they are added.
  document.body.appendChild(container);
  renderRelationships(container, groups, emptyGraph(), recorder().handlers);
  return container;
}

function section(container: HTMLElement, key: string): HTMLElement | null {
  return container.querySelector<HTMLElement>(`[data-section="${key}"]`);
}

function buttonLabels(card: Element): string[] {
  return Array.from(card.querySelectorAll("ui-button")).map((b) => b.getAttribute("label") ?? "");
}

function rowTexts(card: Element): string[] {
  return Array.from(card.querySelectorAll("ui-text")).map((t) => t.getAttribute("content") ?? "");
}

function cardIn(container: HTMLElement, key: string, id: string): HTMLElement {
  const card = container.querySelector<HTMLElement>(
    `[data-section="${key}"] ui-card[data-relationship-id="${id}"]`,
  );
  assert.ok(card, `card for ${id} in ${key}`);
  return card;
}

test("renders Proposed, Confirmed, Declared and Rejected sections in order with counts", () => {
  const container = render(
    emptyGroups({
      proposed: [relationship(PROPOSED_ID, "proposed", "inferred", 0.8), relationship("b:c.x->d:y.id", "proposed")],
      confirmed: [relationship("a:b.a->c:y.id", "confirmed")],
      declared: [relationship(DECLARED_ID, "declared", "declared")],
    }),
  );

  assert.deepEqual(
    Array.from(container.querySelectorAll("section")).map((s) => s.getAttribute("data-section")),
    ["proposed", "confirmed", "declared", "rejected"],
  );
  assert.deepEqual(
    Array.from(container.querySelectorAll("h2")).map((h) => h.textContent),
    ["Proposed (2)", "Confirmed (1)", "Declared (1)", "Rejected (0)"],
  );
});

test("proposed cards carry summary, origin, confidence and evidence and Confirm/Reject buttons", () => {
  const container = render(
    emptyGroups({
      proposed: [relationship(PROPOSED_ID, "proposed", "inferred", 0.8, ["name-match", "key-name-match"])],
    }),
  );

  const card = cardIn(container, "proposed", PROPOSED_ID);
  assert.equal(card.getAttribute("title"), "billing · invoices.user_id → demo · users.id");
  assert.deepEqual(rowTexts(card), [
    "Origin: inferred",
    "Confidence: 80%",
    "Evidence: column name match, key name match",
  ]);
  assert.deepEqual(buttonLabels(card), ["Confirm", "Reject"]);

  // The rows are upgraded components: the text really renders, via textContent.
  const confidenceRow = card.querySelector('ui-text[content="Confidence: 80%"]');
  assert.equal(confidenceRow?.shadowRoot?.querySelector('[part="text"]')?.textContent, "Confidence: 80%");
});

test("confidence is shown only when present", () => {
  const container = render(emptyGroups({ proposed: [relationship(PROPOSED_ID, "proposed")] }));
  assert.deepEqual(rowTexts(cardIn(container, "proposed", PROPOSED_ID)), [
    "Origin: inferred",
    "Evidence: none",
  ]);
});

test("confirmed and rejected cards have Undo; declared cards have none", () => {
  const confirmedId = "a:b.a->c:y.id";
  const rejectedId = "z:q.r->c:y.id";
  const container = render(
    emptyGroups({
      confirmed: [relationship(confirmedId, "confirmed")],
      declared: [relationship(DECLARED_ID, "declared", "declared")],
      rejected: [relationship(rejectedId, "rejected")],
    }),
  );

  assert.deepEqual(buttonLabels(cardIn(container, "confirmed", confirmedId)), ["Undo"]);
  assert.deepEqual(buttonLabels(cardIn(container, "rejected", rejectedId)), ["Undo"]);
  assert.equal(cardIn(container, "declared", DECLARED_ID).querySelectorAll("ui-button").length, 0);
});

test("manual relationships additionally get a Remove button", () => {
  const manualId = "demo:users.id->billing:invoices.customer_id";
  const container = render(
    emptyGroups({ confirmed: [relationship(manualId, "confirmed", "manual")] }),
  );
  assert.deepEqual(buttonLabels(cardIn(container, "confirmed", manualId)), ["Undo", "Remove"]);
});

test("empty sections show a ui-text message instead of nothing", () => {
  const container = render(emptyGroups({}));
  const rejected = section(container, "rejected");
  assert.ok(rejected);
  assert.equal(rejected.querySelectorAll("ui-card").length, 0);
  assert.equal(
    rejected.querySelector("ui-text")?.getAttribute("content"),
    "Nothing has been rejected.",
  );
});

test("clicking a card button calls the matching handler with the relationship id", () => {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const { handlers, calls } = recorder();
  const groups = emptyGroups({
    proposed: [relationship(PROPOSED_ID, "proposed")],
    confirmed: [relationship("demo:users.id->billing:invoices.customer_id", "confirmed", "manual")],
  });
  renderRelationships(container, groups, emptyGraph(), handlers);

  cardIn(container, "proposed", PROPOSED_ID)
    .querySelector<HTMLElement>('ui-button[label="Confirm"]')!
    .click();
  cardIn(container, "proposed", PROPOSED_ID)
    .querySelector<HTMLElement>('ui-button[label="Reject"]')!
    .click();
  cardIn(container, "confirmed", "demo:users.id->billing:invoices.customer_id")
    .querySelector<HTMLElement>('ui-button[label="Undo"]')!
    .click();
  cardIn(container, "confirmed", "demo:users.id->billing:invoices.customer_id")
    .querySelector<HTMLElement>('ui-button[label="Remove"]')!
    .click();

  assert.deepEqual(calls, [
    `confirm:${PROPOSED_ID}`,
    `reject:${PROPOSED_ID}`,
    "undo:demo:users.id->billing:invoices.customer_id",
    "remove:demo:users.id->billing:invoices.customer_id",
  ]);
});

test("setRelationshipButtonsEnabled disables and re-enables a card's buttons", () => {
  const container = render(emptyGroups({ proposed: [relationship(PROPOSED_ID, "proposed")] }));
  const list = container;
  const buttons = () => Array.from(container.querySelectorAll("ui-button"));

  setRelationshipButtonsEnabled(list, PROPOSED_ID, false);
  assert.ok(buttons().length > 0);
  assert.ok(buttons().every((b) => b.hasAttribute("disabled")));

  setRelationshipButtonsEnabled(list, PROPOSED_ID, true);
  assert.ok(buttons().every((b) => !b.hasAttribute("disabled")));

  // An id that is not on screen changes nothing and throws nothing.
  setRelationshipButtonsEnabled(list, "ghost:id->none:there", false);
  assert.ok(buttons().every((b) => !b.hasAttribute("disabled")));
});
