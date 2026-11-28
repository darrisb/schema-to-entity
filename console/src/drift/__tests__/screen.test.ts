import test from "node:test";
import assert from "node:assert/strict";
import { installDom } from "../../test-support/dom.js";
import { ApiError, type ApiClient } from "../../api/client.js";
import type { DriftReport, SchemaChange } from "../../api/types.js";
import { startDrift } from "../screen.js";

installDom();

const { ComponentRegistry } = await import("@visual-ui/core");
const { registerNativeComponents } = await import("@visual-ui/components");
registerNativeComponents(new ComponentRegistry());

function change(fields: Partial<SchemaChange> & Pick<SchemaChange, "type">): SchemaChange {
  return {
    severity: "BREAKING",
    entity: "users",
    column: null,
    before: null,
    after: null,
    relatedTo: null,
    ...fields,
  };
}

/** A changed report: one removal and a matching rename candidate. */
const DEMO: DriftReport = {
  source: "demo",
  status: "changed",
  baselineFingerprint: "base-aaa",
  currentFingerprint: "cur-111",
  baselineTakenAt: "2026-11-24T09:15:00Z",
  checkedAt: "2026-11-25T09:30:00Z",
  changes: [
    change({ type: "COLUMN_REMOVED", column: "user_name", before: "varchar(40) nullable" }),
    change({
      type: "COLUMN_RENAME_CANDIDATE",
      severity: "REVIEW",
      column: "user_name",
      before: "varchar(40) nullable",
      after: "varchar(40) nullable",
      relatedTo: "username",
    }),
  ],
  impact: [
    {
      change: change({ type: "COLUMN_REMOVED", column: "user_name" }),
      labels: ["demo:users.user_name"],
      decisions: [],
      manualRelationships: [],
      foreignKeys: [],
      pages: [],
    },
    {
      change: change({ type: "COLUMN_RENAME_CANDIDATE", column: "user_name", relatedTo: "username" }),
      labels: [],
      decisions: [],
      manualRelationships: [],
      foreignKeys: [],
      pages: [{ pageId: "customer-admin", componentIds: ["users-table"] }],
    },
  ],
  error: null,
};

const BILLING: DriftReport = {
  source: "billing",
  status: "unchanged",
  baselineFingerprint: "base-bbb",
  currentFingerprint: "base-bbb",
  baselineTakenAt: "2026-11-20T08:00:00Z",
  checkedAt: "2026-11-25T09:30:00Z",
  changes: [],
  impact: [],
  error: null,
};

const BROKEN: DriftReport = {
  source: "legacy",
  status: "unavailable",
  baselineFingerprint: "base-ccc",
  currentFingerprint: null,
  baselineTakenAt: "2026-11-01T08:00:00Z",
  checkedAt: "2026-11-25T09:31:00Z",
  changes: [],
  impact: [],
  error: "Schema discovery failed",
};

interface Call {
  method: string;
  args: unknown[];
}

/**
 * A fake ApiClient that records every drift call. getDrift replays the queued
 * report lists (the last one repeats), acceptDrift and confirmRename resolve
 * with behaviour.accept / .renameError when set. Non-drift methods fail.
 */
function fakeClient(
  reportLists: DriftReport[][],
  behaviour: { acceptError?: unknown; renameError?: unknown } = {},
): { client: ApiClient; calls: Call[] } {
  const calls: Call[] = [];
  let loads = 0;
  const fail = async (): Promise<never> => {
    throw new Error("method not expected by this screen");
  };
  const client: ApiClient = {
    getGraph: fail,
    decideRelationship: fail,
    addManualRelationship: fail,
    removeRelationship: fail,
    setLabel: fail,
    removeLabel: fail,
    listEntities: fail,
    queryEntity: fail,
    getDrift: async (check): Promise<DriftReport[]> => {
      calls.push({ method: "getDrift", args: [check] });
      const list = reportLists[Math.min(loads, reportLists.length - 1)];
      loads += 1;
      return list;
    },
    acceptDrift: async (source, fingerprint): Promise<DriftReport> => {
      calls.push({ method: "acceptDrift", args: [source, fingerprint] });
      if (behaviour.acceptError !== undefined) {
        throw behaviour.acceptError;
      }
      return { ...DEMO, status: "unchanged", changes: [], impact: [] };
    },
    confirmRename: async (source, from, to): Promise<void> => {
      calls.push({ method: "confirmRename", args: [source, from, to] });
      if (behaviour.renameError !== undefined) {
        throw behaviour.renameError;
      }
    },
  };
  return { client, calls };
}

async function mount(
  client: ApiClient,
  confirmResult = true,
): Promise<{ container: HTMLElement; done: () => Promise<void> }> {
  window.confirm = (): boolean => confirmResult;
  const container = document.createElement("div");
  document.body.appendChild(container);
  const done = startDrift(container, client);
  await done();
  return { container, done };
}

function card(container: HTMLElement, source: string): HTMLElement {
  const found = container.querySelector<HTMLElement>(`ui-card[data-source='${source}']`);
  assert.ok(found, `the ${source} card is on screen`);
  return found;
}

function button(cardEl: HTMLElement, action: string): HTMLElement {
  const found = cardEl.querySelector<HTMLElement>(`ui-button[data-action='${action}']`);
  assert.ok(found, `the ${action} button is on the card`);
  return found;
}

function statusContent(cardEl: HTMLElement): string {
  return cardEl.querySelector("ui-text[data-drift='status']")?.getAttribute("content") ?? "";
}

test("the first load serves remembered reports and renders one card per source", async () => {
  const { client, calls } = fakeClient([[DEMO, BILLING, BROKEN]]);
  const { container } = await mount(client);

  assert.deepEqual(calls, [{ method: "getDrift", args: [false] }]);
  assert.equal(container.querySelectorAll("ui-card[data-source]").length, 3);
  assert.equal(card(container, "demo").getAttribute("title"), "demo");
});

test("each card shows its status and checked time; an unavailable card shows its error", async () => {
  const { client } = fakeClient([[DEMO, BILLING, BROKEN]]);
  const { container } = await mount(client);

  const demoCard = card(container, "demo");
  assert.match(statusContent(demoCard), /Changed — 2 changes/);
  assert.match(
    [...demoCard.querySelectorAll("ui-text")].map((t) => t.getAttribute("content")).join("\n"),
    /Checked 2026-11-25T09:30:00Z/,
  );

  assert.match(statusContent(card(container, "billing")), /Unchanged since the baseline/);

  const legacyCard = card(container, "legacy");
  assert.match(statusContent(legacyCard), /Unavailable — Schema discovery failed/);
});

test("a changed card groups changes by severity with impact lines", async () => {
  const { client } = fakeClient([[DEMO]]);
  const { container } = await mount(client);
  const demoCard = card(container, "demo");

  const severities = [...demoCard.querySelectorAll<HTMLElement>("[data-severity]")].map(
    (el) => el.getAttribute("data-severity"),
  );
  assert.deepEqual(severities, ["BREAKING", "REVIEW"], "breaking before review");

  const impacts = [...demoCard.querySelectorAll("ui-text[data-drift='impact']")].map((el) =>
    el.getAttribute("content"),
  );
  assert.ok(impacts.some((line) => line?.includes("Labels: demo:users.user_name")));
  assert.ok(impacts.some((line) => line?.includes("Pages: customer-admin (users-table)")));
});

test("only a changed source offers Accept as baseline", async () => {
  const { client } = fakeClient([[DEMO, BILLING, BROKEN]]);
  const { container } = await mount(client);

  assert.ok(button(card(container, "demo"), "accept"), "changed source has Accept");
  assert.equal(
    card(container, "billing").querySelector("ui-button[data-action='accept']"),
    null,
    "unchanged source has no Accept",
  );
  assert.equal(
    card(container, "legacy").querySelector("ui-button[data-action='accept']"),
    null,
    "unavailable source has no Accept",
  );
});

test("Check now runs a fresh check with getDrift(true)", async () => {
  const { client, calls } = fakeClient([[DEMO], [BILLING]]);
  const { container, done } = await mount(client);

  const checkNow = container.querySelector<HTMLElement>("ui-button[data-action='check']");
  assert.ok(checkNow);
  checkNow.click();
  await done();

  assert.deepEqual(calls, [
    { method: "getDrift", args: [false] },
    { method: "getDrift", args: [true] },
  ]);
  assert.equal(container.querySelector("ui-card[data-source='demo']"), null, "fresh reports rendered");
});

test("accept sends the report's currentFingerprint after confirm, then re-checks", async () => {
  const { client, calls } = fakeClient([[DEMO], [BILLING]]);
  const { container, done } = await mount(client);

  button(card(container, "demo"), "accept").click();
  await done();

  assert.deepEqual(calls, [
    { method: "getDrift", args: [false] },
    { method: "acceptDrift", args: ["demo", "cur-111"] },
    { method: "getDrift", args: [true] },
  ]);
});

test("a cancelled confirm sends no accept request", async () => {
  const { client, calls } = fakeClient([[DEMO]]);
  const { container, done } = await mount(client, false);

  button(card(container, "demo"), "accept").click();
  await done();

  assert.deepEqual(calls, [{ method: "getDrift", args: [false] }], "nothing but the initial load");
});

test("confirming a rename sends the pair then re-checks", async () => {
  const { client, calls } = fakeClient([[DEMO], [BILLING]]);
  const { container, done } = await mount(client);

  const rename = card(container, "demo").querySelector<HTMLElement>("ui-button[data-action='rename']");
  assert.ok(rename, "the rename candidate has a Confirm rename button");
  assert.equal(rename.getAttribute("data-rename-from"), "demo:users.user_name");
  assert.equal(rename.getAttribute("data-rename-to"), "demo:users.username");
  rename.click();
  await done();

  assert.deepEqual(calls, [
    { method: "getDrift", args: [false] },
    { method: "confirmRename", args: ["demo", "demo:users.user_name", "demo:users.username"] },
    { method: "getDrift", args: [true] },
  ]);
});

test("a failing accept shows the ApiError on that source's card", async () => {
  const { client } = fakeClient([[DEMO]], { acceptError: new ApiError(409, "A newer check exists") });
  const { container, done } = await mount(client);

  button(card(container, "demo"), "accept").click();
  await done();

  const demoCard = card(container, "demo");
  const error = demoCard.querySelector<HTMLElement>(".drift-error");
  assert.ok(error, "the card carries an error area");
  assert.equal(error.hidden, false);
  assert.equal(error.textContent, "A newer check exists");
  assert.equal(button(demoCard, "accept").hasAttribute("disabled"), false, "Accept is freed again");
});

test("a getDrift failure is shown in the screen error area", async () => {
  const fail: ApiClient = {
    getGraph: async () => {
      throw new Error("no");
    },
    decideRelationship: async () => {},
    addManualRelationship: async () => ({ id: "x" }),
    removeRelationship: async () => {},
    setLabel: async () => {},
    removeLabel: async () => {},
    listEntities: async () => [],
    queryEntity: async () => ({ items: [], total: 0, limit: 0, offset: 0 }),
    getDrift: async () => {
      throw new ApiError(500, "drift is unavailable");
    },
    acceptDrift: async () => DEMO,
    confirmRename: async () => {},
  };
  const container = document.createElement("div");
  document.body.appendChild(container);
  const done = startDrift(container, fail);
  await done();

  const area = container.querySelector<HTMLElement>(".screen-error:not(.drift-error)");
  assert.ok(area);
  assert.equal(area.hidden, false);
  assert.equal(area.textContent, "drift is unavailable");
});
