import test from "node:test";
import assert from "node:assert/strict";
import type {
  GraphColumn,
  GraphEntity,
  GraphRelationship,
  RelationshipStatus,
  SchemaGraph,
} from "../../api/types.js";
import {
  describeEnd,
  evidenceText,
  groupRelationships,
  isCrossSource,
  summary,
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
  status: RelationshipStatus,
  options: {
    scope?: GraphRelationship["scope"];
    confidence?: number | null;
    evidence?: string[];
  } = {},
): GraphRelationship {
  return {
    id,
    from,
    to,
    origin: status === "declared" ? "declared" : "inferred",
    scope: options.scope ?? (from.split(":")[0] === to.split(":")[0] ? "within-source" : "cross-source"),
    status,
    confidence: options.confidence ?? null,
    evidence: options.evidence ?? [],
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

/** The demo's shape: declared keys in each source, a cross-source proposal. */
function demoGraph(relationships: GraphRelationship[]): SchemaGraph {
  return graph(
    [
      entity("billing:invoices", "billing", "invoices", [
        column("billing:invoices.id", "id"),
        column("billing:invoices.user_id", "user_id"),
      ]),
      entity("demo:users", "demo", "users", [
        column("demo:users.id", "id"),
        column("demo:users.username", "username"),
      ]),
    ],
    relationships,
  );
}

test("groupRelationships buckets by status", () => {
  const groups = groupRelationships(
    demoGraph([
      relationship("a:b.x->c:y.id", "a:b.x", "c:y.id", "proposed", { confidence: 0.6 }),
      relationship("d:e.y->f:g.id", "d:e.y", "f:g.id", "confirmed", { scope: "within-source" }),
      relationship("demo:posts.author_id->demo:users.id", "demo:posts.author_id", "demo:users.id", "declared", {
        scope: "within-source",
      }),
      relationship("g:h.z->i:j.id", "g:h.z", "i:j.id", "rejected", { scope: "within-source" }),
    ]),
  );

  assert.deepEqual(groups.proposed.map((r) => r.id), ["a:b.x->c:y.id"]);
  assert.deepEqual(groups.confirmed.map((r) => r.id), ["d:e.y->f:g.id"]);
  assert.deepEqual(groups.declared.map((r) => r.id), ["demo:posts.author_id->demo:users.id"]);
  assert.deepEqual(groups.rejected.map((r) => r.id), ["g:h.z->i:j.id"]);
});

test("proposed sorts by confidence high to low, null confidence last, ties by id", () => {
  const groups = groupRelationships(
    demoGraph([
      relationship("z:low.a->demo:users.id", "z:low.a", "demo:users.id", "proposed", { confidence: 0.6 }),
      relationship("z:high.a->demo:users.id", "z:high.a", "demo:users.id", "proposed", { confidence: 0.9 }),
      relationship("z:none.a->demo:users.id", "z:none.a", "demo:users.id", "proposed", { confidence: null }),
      relationship("z:tie.b->demo:users.id", "z:tie.b", "demo:users.id", "proposed", { confidence: 0.8 }),
      relationship("z:tie.a->demo:users.id", "z:tie.a", "demo:users.id", "proposed", { confidence: 0.8 }),
    ]),
  );

  assert.deepEqual(groups.proposed.map((r) => r.id), [
    "z:high.a->demo:users.id",
    "z:tie.a->demo:users.id",
    "z:tie.b->demo:users.id",
    "z:low.a->demo:users.id",
    "z:none.a->demo:users.id",
  ]);
});

test("confirmed, declared and rejected sort by id", () => {
  const groups = groupRelationships(
    demoGraph([
      relationship("m:n.q->demo:users.id", "m:n.q", "demo:users.id", "confirmed", { scope: "within-source" }),
      relationship("b:c.d->demo:users.id", "b:c.d", "demo:users.id", "confirmed", { scope: "within-source" }),
      relationship("y:z.k->demo:users.id", "y:z.k", "demo:users.id", "declared", { scope: "within-source" }),
      relationship("c:d.e->demo:users.id", "c:d.e", "demo:users.id", "declared", { scope: "within-source" }),
      relationship("w:x.j->demo:users.id", "w:x.j", "demo:users.id", "rejected", { scope: "within-source" }),
      relationship("p:q.r->demo:users.id", "p:q.r", "demo:users.id", "rejected", { scope: "within-source" }),
    ]),
  );

  assert.deepEqual(groups.confirmed.map((r) => r.id), [
    "b:c.d->demo:users.id",
    "m:n.q->demo:users.id",
  ]);
  assert.deepEqual(groups.declared.map((r) => r.id), [
    "c:d.e->demo:users.id",
    "y:z.k->demo:users.id",
  ]);
  assert.deepEqual(groups.rejected.map((r) => r.id), [
    "p:q.r->demo:users.id",
    "w:x.j->demo:users.id",
  ]);
});

test("rejected is empty unless the graph was fetched with includeRejected=true", () => {
  // GET /api/graph?includeRejected=false never carries rejected rows.
  const hidden = groupRelationships(demoGraph([
    relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "proposed", {
      confidence: 0.8,
    }),
  ]));
  assert.deepEqual(hidden.rejected, []);

  // includeRejected=true brings them back.
  const shown = groupRelationships(demoGraph([
    relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "proposed", {
      confidence: 0.8,
    }),
    relationship("billing:payments.payer_id->demo:users.id", "billing:payments.payer_id", "demo:users.id", "rejected"),
  ]));
  assert.deepEqual(shown.rejected.map((r) => r.id), [
    "billing:payments.payer_id->demo:users.id",
  ]);
});

test("groupRelationships does not mutate the graph", () => {
  const relationships = [
    relationship("b:c.d->demo:users.id", "b:c.d", "demo:users.id", "confirmed", { scope: "within-source" }),
    relationship("a:b.x->demo:users.id", "a:b.x", "demo:users.id", "proposed", { confidence: 0.6 }),
  ];
  const source = demoGraph(relationships);
  // demoGraph stores this array by reference, so comparing the ids against a
  // snapshot catches an in-place sort of graph.relationships; deepEqual of the
  // array against itself would always pass.
  const before = relationships.map((r) => r.id);
  const groups = groupRelationships(source);

  assert.deepEqual(relationships.map((r) => r.id), before);
  assert.notEqual(groups.proposed, relationships);
  assert.notEqual(groups.confirmed, relationships);
  assert.notEqual(groups.declared, relationships);
  assert.notEqual(groups.rejected, relationships);
});

test("describeEnd uses overlay labels when set and names otherwise", () => {
  const labeled = demoGraph([]);
  labeled.entities[0].label = "Invoices";
  labeled.entities[0].columns[1].label = "Customer";
  labeled.entities[1].label = "Users";

  assert.deepEqual(describeEnd(labeled, "billing:invoices.user_id"), {
    source: "billing",
    entityLabel: "Invoices",
    columnLabel: "Customer",
  });
  // A column with no overlay label keeps its discovered name; the entity's
  // overlay label still applies.
  assert.deepEqual(describeEnd(labeled, "demo:users.id"), {
    source: "demo",
    entityLabel: "Users",
    columnLabel: "id",
  });
});

test("describeEnd falls back to the raw id for unknown columns", () => {
  const source = demoGraph([]);
  // The column is gone but the entity remains: entity label still applies, the
  // column comes from the raw id.
  assert.deepEqual(describeEnd(source, "demo:users.deleted_at"), {
    source: "demo",
    entityLabel: "users",
    columnLabel: "deleted_at",
  });

  // Nothing in the document at all: every part comes from the raw id.
  assert.deepEqual(describeEnd(source, "legacy:orders.buyer_id"), {
    source: "legacy",
    entityLabel: "orders",
    columnLabel: "buyer_id",
  });

  // Not even source:entity.column form: the whole raw id everywhere.
  assert.deepEqual(describeEnd(source, "mystery-id"), {
    source: "mystery-id",
    entityLabel: "mystery-id",
    columnLabel: "mystery-id",
  });
});

test("summary reads as 'billing · invoices.user_id → demo · users.id'", () => {
  const source = demoGraph([]);
  const line = summary(
    relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "proposed", {
      confidence: 0.8,
    }),
    source,
  );
  assert.equal(line, "billing · invoices.user_id → demo · users.id");
});

test("summary shows overlay labels", () => {
  const source = demoGraph([]);
  source.entities[0].label = "Invoices";
  source.entities[0].columns[1].label = "Customer";
  source.entities[1].label = "Users";
  const line = summary(
    relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "proposed", {
      confidence: 0.8,
    }),
    source,
  );
  assert.equal(line, "billing · Invoices.Customer → demo · Users.id");
});

test("evidenceText turns codes into phrases and passes unknown codes through", () => {
  const text = evidenceText(
    relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "proposed", {
      confidence: 0.8,
      evidence: ["name-match", "type-match", "key-name-match", "brand-new-signal"],
    }),
  );
  assert.equal(text, "column name match, column type match, key name match, brand-new-signal");

  assert.equal(evidenceText(relationship("v:v.a->demo:users.id", "v:v.a", "demo:users.id", "proposed")), "");
  assert.equal(
    evidenceText(
      relationship("demo:v.a->demo:users.id", "demo:view_base.id", "demo:users.id", "proposed", {
        evidence: ["view-column-name"],
      }),
    ),
    "view column named like the base key",
  );
});

test("isCrossSource reads the scope", () => {
  assert.equal(
    isCrossSource(
      relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "proposed", {
        scope: "cross-source",
      }),
    ),
    true,
  );
  assert.equal(
    isCrossSource(
      relationship("demo:posts.author_id->demo:users.id", "demo:posts.author_id", "demo:users.id", "declared", {
        scope: "within-source",
      }),
    ),
    false,
  );
});

test("isCrossSource detects differing source prefixes even when scope says within-source", () => {
  assert.equal(
    isCrossSource(
      relationship("billing:invoices.user_id->demo:users.id", "billing:invoices.user_id", "demo:users.id", "confirmed", {
        scope: "within-source",
      }),
    ),
    true,
  );
  // Ids without source prefixes cannot be shown to differ.
  assert.equal(
    isCrossSource(relationship("plain-a", "plain-a", "plain-b", "proposed", { scope: "within-source" })),
    false,
  );
});
