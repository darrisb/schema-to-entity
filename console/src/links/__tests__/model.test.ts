import test from "node:test";
import assert from "node:assert/strict";
import type {
  GraphColumn,
  GraphEntity,
  GraphRelationship,
  RelationshipOrigin,
  SchemaGraph,
} from "../../api/types.js";
import {
  columnOptions,
  entityOptions,
  manualRelationships,
  validateLink,
} from "../model.js";

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

function relationship(
  id: string,
  from: string,
  to: string,
  origin: RelationshipOrigin,
): GraphRelationship {
  return {
    id,
    from,
    to,
    origin,
    scope: from.split(":")[0] === to.split(":")[0] ? "within-source" : "cross-source",
    status: origin === "declared" ? "declared" : "confirmed",
    confidence: null,
    evidence: [],
  };
}

function graph(entities: GraphEntity[], relationships: GraphRelationship[]): SchemaGraph {
  return {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities,
    relationships,
    orphans: [],
  };
}

/** The demo's shape: a labelled users table, a plain invoices table. */
function demoGraph(relationships: GraphRelationship[] = []): SchemaGraph {
  return graph(
    [
      entity("billing:invoices", "billing", "invoices", [
        column("billing:invoices.id", "id"),
        column("billing:invoices.customer_id", "customer_id", "Customer ref"),
      ]),
      entity("demo:users", "demo", "users", [
        column("demo:users.id", "id"),
        column("demo:users.username", "username", "User name"),
      ], "Users"),
    ],
    relationships,
  );
}

test("entityOptions lists every entity by id with a source-prefixed label", () => {
  const options = entityOptions(demoGraph());
  assert.deepEqual(options, [
    { value: "billing:invoices", label: "billing · invoices" },
    { value: "demo:users", label: "demo · Users" },
  ]);
});

test("entityOptions sorts by id so sources group and stay stable", () => {
  const shuffled = graph(
    [
      entity("demo:posts", "demo", "posts", []),
      entity("billing:invoices", "billing", "invoices", []),
      entity("demo:users", "demo", "users", []),
    ],
    [],
  );
  assert.deepEqual(
    entityOptions(shuffled).map((option) => option.value),
    ["billing:invoices", "demo:posts", "demo:users"],
  );
});

test("columnOptions returns one entity's columns in discovery order with label fallback", () => {
  assert.deepEqual(columnOptions(demoGraph(), "demo:users"), [
    { value: "demo:users.id", label: "id" },
    { value: "demo:users.username", label: "User name" },
  ]);
});

test("columnOptions returns no options for an entity the document does not carry", () => {
  assert.deepEqual(columnOptions(demoGraph(), "demo:ghosts"), []);
});

test("validateLink accepts two different chosen columns", () => {
  assert.equal(validateLink("demo:users.id", "billing:invoices.customer_id"), null);
});

test("validateLink refuses a pair with a missing side", () => {
  assert.match(validateLink("", "demo:users.id") ?? "", /both sides/i);
  assert.match(validateLink("demo:users.id", "") ?? "", /both sides/i);
  assert.match(validateLink("", "") ?? "", /both sides/i);
});

test("validateLink refuses a link whose two ends are the same column", () => {
  const message = validateLink("demo:users.id", "demo:users.id");
  assert.ok(message);
  assert.match(message, /same column/i);
});

test("manualRelationships keeps only manual origins, sorted by id", () => {
  const declared = relationship(
    "demo:posts.author_id->demo:users.id",
    "demo:posts.author_id",
    "demo:users.id",
    "declared",
  );
  const inferred = relationship(
    "billing:invoices.user_id->demo:users.id",
    "billing:invoices.user_id",
    "demo:users.id",
    "inferred",
  );
  const manualB = relationship("demo:users.id->billing:invoices.paid_to", "demo:users.id", "billing:invoices.paid_to", "manual");
  const manualA = relationship("demo:users.id->billing:invoices.account_id", "demo:users.id", "billing:invoices.account_id", "manual");
  const source = graph([], [declared, manualB, inferred, manualA]);

  assert.deepEqual(
    manualRelationships(source).map((item) => item.id),
    [manualA.id, manualB.id],
  );
  // The caller's array order is untouched.
  assert.deepEqual(
    source.relationships.map((item) => item.id),
    [declared.id, manualB.id, inferred.id, manualA.id],
  );
});

test("manualRelationships is empty when nothing was asserted by hand", () => {
  assert.deepEqual(manualRelationships(demoGraph()), []);
});
