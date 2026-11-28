import test from "node:test";
import assert from "node:assert/strict";
import type { ChangeImpact, DriftReport, SchemaChange } from "../../api/types.js";
import {
  acceptMessage,
  canAccept,
  changeText,
  checkedText,
  groupChanges,
  impactText,
  renameCandidate,
  renameCandidates,
  severityOrder,
  statusText,
} from "../model.js";

/** One SchemaChange, everything but the named fields empty. */
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

test("changeText reads one line per change type", () => {
  assert.equal(changeText(change({ type: "ENTITY_ADDED", after: "table" })), "Entity users added (table)");
  assert.equal(
    changeText(change({ type: "ENTITY_REMOVED", entity: "posts", before: "view" })),
    "Entity posts removed (view)",
  );
  assert.equal(
    changeText(change({ type: "ENTITY_KIND_CHANGED", before: "table", after: "view" })),
    "Entity users changed kind from table to view",
  );
  assert.equal(
    changeText(
      change({
        type: "ENTITY_RENAME_CANDIDATE",
        severity: "REVIEW",
        entity: "customers",
        before: "table",
        after: "table",
        relatedTo: "accounts",
      }),
    ),
    "Entity customers may be renamed to accounts (table → table)",
  );
  assert.equal(
    changeText(change({ type: "COLUMN_ADDED", column: "username", after: "varchar(40) nullable" })),
    "Column users.username added (varchar(40) nullable)",
  );
  assert.equal(
    changeText(change({ type: "COLUMN_REMOVED", column: "user_name", before: "varchar(40) nullable" })),
    "Column users.user_name removed (varchar(40) nullable)",
  );
  assert.equal(
    changeText(
      change({ type: "COLUMN_TYPE_CHANGED", column: "email", before: "varchar(80)", after: "varchar(120)" }),
    ),
    "Column users.email changed type from varchar(80) to varchar(120)",
  );
  assert.equal(
    changeText(
      change({
        type: "COLUMN_NULLABILITY_CHANGED",
        severity: "BREAKING",
        column: "email",
        before: "nullable",
        after: "not null",
      }),
    ),
    "Column users.email changed nullability from nullable to not null",
  );
  assert.equal(
    changeText(
      change({
        type: "COLUMN_RENAME_CANDIDATE",
        severity: "REVIEW",
        column: "user_name",
        before: "varchar(40) nullable",
        after: "varchar(40) nullable",
        relatedTo: "username",
      }),
    ),
    "Column users.user_name may be renamed to username (varchar(40) nullable → varchar(40) nullable)",
  );
  assert.equal(
    changeText(change({ type: "KEY_COLUMNS_CHANGED", before: "id", after: "tenant_id, id" })),
    "Key of users changed from id to tenant_id, id",
  );
  assert.equal(
    changeText(change({ type: "FOREIGN_KEY_ADDED", severity: "ADDITIVE", entity: "posts", column: "author_id", after: "users.id" })),
    "Foreign key on posts.author_id added (users.id)",
  );
  assert.equal(
    changeText(change({ type: "FOREIGN_KEY_REMOVED", entity: "posts", column: "author_id", before: "users.id" })),
    "Foreign key on posts.author_id removed (users.id)",
  );
});

test("changeText omits a state that is absent rather than printing null", () => {
  // A defensive spelling the API does not send: an add with no after state.
  assert.equal(changeText(change({ type: "COLUMN_ADDED", column: "username" })), "Column users.username added");
});

test("severityOrder puts BREAKING before REVIEW before ADDITIVE", () => {
  assert.equal(severityOrder("BREAKING"), 0);
  assert.equal(severityOrder("REVIEW"), 1);
  assert.equal(severityOrder("ADDITIVE"), 2);

  const shuffled: SchemaChange["severity"][] = ["ADDITIVE", "REVIEW", "BREAKING"];
  assert.deepEqual([...shuffled].sort((a, b) => severityOrder(a) - severityOrder(b)), [
    "BREAKING",
    "REVIEW",
    "ADDITIVE",
  ]);
});

test("impactText lists every non-empty bucket and the affected pages", () => {
  const impact: ChangeImpact = {
    change: change({ type: "COLUMN_REMOVED", column: "user_name" }),
    labels: ["demo:users.user_name"],
    decisions: ["demo:posts.author_id->demo:users.id"],
    manualRelationships: ["demo:users.id->billing:invoices.customer_id"],
    foreignKeys: ["demo:posts.author_id->demo:users.id"],
    pages: [{ pageId: "customer-admin", componentIds: ["users-table", "users-filter"] }],
  };
  assert.equal(
    impactText(impact),
    "Affects — Labels: demo:users.user_name; Decisions: demo:posts.author_id->demo:users.id; " +
      "Manual relationships: demo:users.id->billing:invoices.customer_id; " +
      "Foreign keys: demo:posts.author_id->demo:users.id; " +
      "Pages: customer-admin (users-table, users-filter)",
  );
});

test("impactText says so when nothing refers to the change", () => {
  const impact: ChangeImpact = {
    change: change({ type: "COLUMN_ADDED", severity: "ADDITIVE", column: "username" }),
    labels: [],
    decisions: [],
    manualRelationships: [],
    foreignKeys: [],
    pages: [],
  };
  assert.equal(impactText(impact), "Nothing refers to this change.");
});

/** A report holding only the given changes, with no impact entries. */
function reportWith(source: string, changes: SchemaChange[]): DriftReport {
  return {
    source,
    status: "changed",
    baselineFingerprint: "baseline",
    currentFingerprint: "current",
    baselineTakenAt: "2026-11-24T09:15:00Z",
    checkedAt: "2026-11-25T09:30:00Z",
    changes,
    impact: [],
    error: null,
  };
}

test("renameCandidates builds source:entity.column ids for column candidates", () => {
  const report = reportWith("demo", [
    change({
      type: "COLUMN_RENAME_CANDIDATE",
      severity: "REVIEW",
      entity: "USERS",
      column: "user_name",
      before: "varchar(40) nullable",
      after: "varchar(40) nullable",
      relatedTo: "USERNAME",
    }),
  ]);
  assert.deepEqual(renameCandidates(report), [
    { from: "demo:users.user_name", to: "demo:users.username" },
  ]);
});

test("renameCandidates builds source:entity ids for entity candidates", () => {
  const report = reportWith("demo", [
    change({
      type: "ENTITY_RENAME_CANDIDATE",
      severity: "REVIEW",
      entity: "customers",
      before: "table",
      after: "table",
      relatedTo: "accounts",
    }),
  ]);
  assert.deepEqual(renameCandidates(report), [{ from: "demo:customers", to: "demo:accounts" }]);
});

test("renameCandidates skips non-candidates and a candidate naming no counterpart", () => {
  const report = reportWith("demo", [
    change({ type: "COLUMN_REMOVED", column: "user_name", before: "varchar(40)" }),
    // A candidate-shaped change whose relatedTo is null cannot be confirmed.
    change({ type: "ENTITY_RENAME_CANDIDATE", severity: "REVIEW", entity: "orders", after: "table" }),
  ]);
  assert.deepEqual(renameCandidates(report), []);
  assert.equal(renameCandidate(report, report.changes[1]), null);
});

test("renameCandidate pairs a column within its entity, lower-casing every part", () => {
  const candidate = change({
    type: "COLUMN_RENAME_CANDIDATE",
    severity: "REVIEW",
    entity: "Users",
    column: "User_Name",
    relatedTo: "Username",
  });
  assert.deepEqual(renameCandidate(reportWith("Billing", [candidate]), candidate), {
    from: "billing:users.user_name",
    to: "billing:users.username",
  });
});

test("groupChanges buckets a changed report by severity, review order, keeping impact aligned", () => {
  const breaking = change({ type: "COLUMN_REMOVED", column: "legacy" });
  const review = change({
    type: "COLUMN_RENAME_CANDIDATE",
    severity: "REVIEW",
    column: "user_name",
    relatedTo: "username",
  });
  const additive = change({ type: "ENTITY_ADDED", severity: "ADDITIVE", entity: "orders", after: "table" });
  const report: DriftReport = {
    ...reportWith("demo", [additive, breaking, review]),
    impact: [
      { change: additive, labels: [], decisions: [], manualRelationships: [], foreignKeys: [], pages: [] },
      { change: breaking, labels: ["demo:users.legacy"], decisions: [], manualRelationships: [], foreignKeys: [], pages: [] },
      { change: review, labels: [], decisions: [], manualRelationships: [], foreignKeys: [], pages: [] },
    ],
  };
  const groups = groupChanges(report);
  assert.deepEqual(groups.map((group) => group.severity), ["BREAKING", "REVIEW", "ADDITIVE"]);
  // Each change kept the impact entry at its own index, not its group position.
  assert.equal(groups[0].changes[0].impact?.labels[0], "demo:users.legacy");
  assert.equal(groups[1].changes[0].impact === null, false);
  assert.equal(groups[2].changes[0].impact?.labels.length, 0);
});

test("statusText names each status and the checked line carries the instant", () => {
  const changed = reportWith("demo", [change({ type: "COLUMN_REMOVED", column: "a" })]);
  assert.equal(statusText(changed), "Changed — 1 change from the baseline");
  assert.equal(checkedText(changed), "Checked 2026-11-25T09:30:00Z");

  assert.equal(
    statusText({ ...changed, status: "unchanged", changes: [], impact: [] }),
    "Unchanged since the baseline",
  );
  assert.equal(
    statusText({ ...changed, status: "baseline-created", changes: [], impact: [] }),
    "First check — baseline created from this snapshot",
  );
  assert.equal(
    statusText({ ...changed, status: "unavailable", changes: [], impact: [], error: "Schema discovery failed" }),
    "Unavailable — Schema discovery failed",
  );
});

test("only a changed report with a fingerprint can be accepted", () => {
  const changed = reportWith("demo", [change({ type: "COLUMN_REMOVED", column: "a" })]);
  assert.equal(canAccept(changed), true);
  assert.match(acceptMessage(changed), /Accept the current schema of "demo"/);
  assert.equal(canAccept({ ...changed, currentFingerprint: null }), false);
  assert.equal(canAccept({ ...changed, status: "unchanged", changes: [], impact: [] }), false);
});
