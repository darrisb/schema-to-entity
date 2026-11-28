import test from "node:test";
import assert from "node:assert/strict";
import type { GraphColumn, GraphEntity, SchemaGraph } from "../../api/types.js";
import { isDirty, labelChange, labelRows } from "../model.js";

function column(
  id: string,
  name: string,
  type: string,
  label: string | null = null,
  description: string | null = null,
): GraphColumn {
  return { id, name, type, category: "TEXT", nullable: false, label, description };
}

function entity(columns: GraphColumn[], label: string | null = null, description: string | null = null): GraphEntity {
  return {
    id: "demo:users",
    source: "demo",
    name: "users",
    kind: "table",
    label,
    description,
    keyColumns: ["id"],
    columns,
    baseTables: [],
  };
}

function graphWith(...entities: GraphEntity[]): SchemaGraph {
  return {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities,
    relationships: [],
    orphans: [],
  };
}

test("labelRows returns the entity row first, then one row per column in discovery order", () => {
  const graph = graphWith(
    entity([
      column("demo:users.id", "id", "bigserial"),
      column("demo:users.username", "username", "varchar(255)"),
    ]),
    entity([]),
  );

  const rows = labelRows(graph, "demo:users");
  assert.deepEqual(
    rows.map((row) => row.id),
    ["demo:users", "demo:users.id", "demo:users.username"],
  );
  assert.deepEqual(
    rows.map((row) => row.kind),
    ["entity", "column", "column"],
  );
});

test("rows carry the document's label and description, prefilled empty when null", () => {
  const graph = graphWith(
    entity([column("demo:users.id", "id", "bigserial"), column("demo:users.nick", "nick", "text", "Nickname", "What the user is called")], "Users", "Registered accounts"),
  );

  const rows = labelRows(graph, "demo:users");
  assert.deepEqual(
    { label: rows[0].label, description: rows[0].description },
    { label: "Users", description: "Registered accounts" },
  );
  // An unlabelled column starts blank, not with its discovered name.
  assert.deepEqual(
    { label: rows[1].label, description: rows[1].description },
    { label: "", description: "" },
  );
  assert.deepEqual(
    { label: rows[2].label, description: rows[2].description },
    { label: "Nickname", description: "What the user is called" },
  );
});

test("headings name the object, and an unknown entity yields no rows", () => {
  const graph = graphWith(entity([column("demo:users.id", "id", "bigserial")], "Users"));

  const rows = labelRows(graph, "demo:users");
  assert.equal(rows[0].heading, "Entity · demo · users");
  assert.equal(rows[1].heading, "Column · id (bigserial)");
  assert.deepEqual(labelRows(graph, "demo:missing"), []);
});

test("isDirty is true only when a field differs from the prefill", () => {
  const baseline = { label: "Users", description: "Registered accounts" };
  assert.equal(isDirty(baseline, baseline), false);
  assert.equal(isDirty(baseline, { label: "Users", description: "" }), true);
  assert.equal(isDirty(baseline, { label: "People", description: baseline.description }), true);
  // Typing back to the original disables Save again.
  assert.equal(isDirty(baseline, { label: "Users", description: baseline.description }), false);
});

test("labelChange trims both fields and sends a blank description as null", () => {
  assert.deepEqual(
    labelChange({ label: "  Users  ", description: "  Registered accounts  " }),
    { label: "Users", description: "Registered accounts" },
  );
  assert.deepEqual(labelChange({ label: "Users", description: "   " }), {
    label: "Users",
    description: null,
  });
  // A blank label is left blank for the server's 400 to report.
  assert.deepEqual(labelChange({ label: "", description: "x" }), {
    label: "",
    description: "x",
  });
});
