import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, createClient, type FetchImpl } from "../client.js";

/** One recorded call to the fake fetch. */
interface Call {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | null;
}

/**
 * A fake fetch that records every call and returns whatever the responder
 * builds. The real Response from Node's fetch implementation keeps the
 * status, body and statusText the client reads.
 */
function stubFetch(responder: () => Response): { calls: Call[]; fetchImpl: FetchImpl } {
  const calls: Call[] = [];
  const fetchImpl: FetchImpl = async (input, init) => {
    calls.push({
      url: input,
      method: init?.method ?? "GET",
      headers: { ...(init?.headers as Record<string, string> | undefined) },
      body: typeof init?.body === "string" ? init.body : null,
    });
    return responder();
  };
  return { calls, fetchImpl };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function noContent(): Response {
  return new Response(null, { status: 204 });
}

test("getGraph calls GET /api/graph with the encoded includeRejected flag", async () => {
  const graph = {
    specVersion: "1.0",
    generatedAt: "2026-11-27T09:00:00Z",
    sources: [],
    entities: [],
    relationships: [],
    orphans: [],
  };

  const first = stubFetch(() => jsonResponse(graph));
  const client = createClient(first.fetchImpl);
  const result = await client.getGraph(true);
  assert.equal(first.calls[0].url, "/api/graph?includeRejected=true");
  assert.equal(first.calls[0].method, "GET");
  assert.deepEqual(result, graph);

  const second = stubFetch(() => jsonResponse(graph));
  await createClient(second.fetchImpl).getGraph(false);
  assert.equal(second.calls[0].url, "/api/graph?includeRejected=false");
  assert.equal(second.calls[0].headers["Content-Type"], undefined, "GET sends no JSON header");
});

test("decideRelationship PUTs the decision body and resolves undefined on 204", async () => {
  const { calls, fetchImpl } = stubFetch(noContent);
  const client = createClient(fetchImpl);
  const result = await client.decideRelationship("a:x.id->b:y.id", "confirmed");

  assert.equal(result, undefined, "204 resolves to undefined");
  assert.equal(calls[0].url, "/api/graph/overlay/relationships");
  assert.equal(calls[0].method, "PUT");
  assert.equal(calls[0].headers["Content-Type"], "application/json");
  assert.deepEqual(JSON.parse(calls[0].body as string), {
    id: "a:x.id->b:y.id",
    status: "confirmed",
  });
});

test("decideRelationship with null status sends an explicit null to clear the decision", async () => {
  const { calls, fetchImpl } = stubFetch(noContent);
  await createClient(fetchImpl).decideRelationship("a:x.id->b:y.id", null);

  assert.deepEqual(JSON.parse(calls[0].body as string), {
    id: "a:x.id->b:y.id",
    status: null,
  });
});

test("addManualRelationship POSTs the pair and returns the created id", async () => {
  const { calls, fetchImpl } = stubFetch(() =>
    jsonResponse({ id: "demo:users.id->billing:invoices.customer_id" }),
  );
  const client = createClient(fetchImpl);
  const result = await client.addManualRelationship(
    "demo:users.id",
    "billing:invoices.customer_id",
  );

  assert.equal(calls[0].url, "/api/graph/overlay/relationships");
  assert.equal(calls[0].method, "POST");
  assert.equal(calls[0].headers["Content-Type"], "application/json");
  assert.deepEqual(JSON.parse(calls[0].body as string), {
    from: "demo:users.id",
    to: "billing:invoices.customer_id",
  });
  assert.deepEqual(result, { id: "demo:users.id->billing:invoices.customer_id" });
});

test("removeRelationship DELETEs with the relationship id URL-encoded in the query", async () => {
  const { calls, fetchImpl } = stubFetch(noContent);
  await createClient(fetchImpl).removeRelationship(
    "demo:users.id->billing:invoices.customer_id",
  );

  assert.equal(
    calls[0].url,
    "/api/graph/overlay/relationships?id=demo%3Ausers.id-%3Ebilling%3Ainvoices.customer_id",
  );
  assert.equal(calls[0].method, "DELETE");
});

test("setLabel PUTs the label body and removeLabel DELETEs the encoded id", async () => {
  const set = stubFetch(noContent);
  const result = await createClient(set.fetchImpl).setLabel(
    "demo:users",
    "Users",
    null,
  );
  assert.equal(result, undefined);
  assert.equal(set.calls[0].url, "/api/graph/overlay/labels");
  assert.equal(set.calls[0].method, "PUT");
  assert.equal(set.calls[0].headers["Content-Type"], "application/json");
  assert.deepEqual(JSON.parse(set.calls[0].body as string), {
    id: "demo:users",
    label: "Users",
    description: null,
  });

  const remove = stubFetch(noContent);
  await createClient(remove.fetchImpl).removeLabel("demo:users");
  assert.equal(remove.calls[0].url, "/api/graph/overlay/labels?id=demo%3Ausers");
  assert.equal(remove.calls[0].method, "DELETE");
});

test("listEntities GETs the source's entity list", async () => {
  const descriptors = [
    {
      name: "CUSTOMERS",
      kind: "table",
      keyColumns: ["ID"],
      columns: [{ name: "ID", type: "bigint", category: "NUMBER", nullable: false }],
      schemaVersion: "1a2b3c4d5e6f",
    },
  ];
  const { calls, fetchImpl } = stubFetch(() => jsonResponse(descriptors));
  const result = await createClient(fetchImpl).listEntities("demo");

  assert.equal(calls[0].url, "/api/data/demo");
  assert.equal(calls[0].method, "GET");
  assert.deepEqual(result, descriptors);
});

test("queryEntity GETs the envelope and encodes only the options supplied", async () => {
  const page = { items: [{ ID: 2 }], total: 5, limit: 2, offset: 1 };
  const withOptions = stubFetch(() => jsonResponse(page));
  const result = await createClient(withOptions.fetchImpl).queryEntity(
    "demo",
    "customers",
    { limit: 2, offset: 1, sort: "-id" },
  );
  assert.equal(withOptions.calls[0].url, "/api/data/demo/customers?limit=2&offset=1&sort=-id");
  assert.deepEqual(result, page);

  const bare = stubFetch(() => jsonResponse(page));
  await createClient(bare.fetchImpl).queryEntity("demo", "customers", {});
  assert.equal(bare.calls[0].url, "/api/data/demo/customers", "no options means no query string");

  const encoded = stubFetch(() => jsonResponse(page));
  await createClient(encoded.fetchImpl).queryEntity("my source", "my entity", { limit: 10 });
  assert.equal(encoded.calls[0].url, "/api/data/my%20source/my%20entity?limit=10");
});

test("getDrift GETs the reports with the encoded check flag", async () => {
  const reports = [{ source: "demo", status: "unchanged" }];
  const checked = stubFetch(() => jsonResponse(reports));
  const result = await createClient(checked.fetchImpl).getDrift(true);
  assert.equal(checked.calls[0].url, "/api/graph/drift?check=true");
  assert.equal(checked.calls[0].method, "GET");
  assert.deepEqual(result, reports);

  const remembered = stubFetch(() => jsonResponse(reports));
  await createClient(remembered.fetchImpl).getDrift(false);
  assert.equal(remembered.calls[0].url, "/api/graph/drift?check=false");
});

test("acceptDrift POSTs the fingerprint and confirmRename POSTs the pair to 204", async () => {
  const report = { source: "demo", status: "unchanged", changes: [], impact: [] };
  const accept = stubFetch(() => jsonResponse(report));
  const result = await createClient(accept.fetchImpl).acceptDrift("demo", "abc123");
  assert.equal(accept.calls[0].url, "/api/graph/drift/demo/accept");
  assert.equal(accept.calls[0].method, "POST");
  assert.equal(accept.calls[0].headers["Content-Type"], "application/json");
  assert.deepEqual(JSON.parse(accept.calls[0].body as string), { fingerprint: "abc123" });
  assert.deepEqual(result, report);

  const rename = stubFetch(noContent);
  const renamed = await createClient(rename.fetchImpl).confirmRename(
    "demo",
    "demo:users.user_name",
    "demo:users.username",
  );
  assert.equal(renamed, undefined);
  assert.equal(rename.calls[0].url, "/api/graph/drift/demo/renames");
  assert.deepEqual(JSON.parse(rename.calls[0].body as string), {
    from: "demo:users.user_name",
    to: "demo:users.username",
  });
});

test("an error body's field becomes the ApiError message, for error and message keys", async () => {
  const { calls, fetchImpl } = stubFetch(() =>
    jsonResponse({ error: "Unknown relationship: nope" }, 404),
  );
  const client = createClient(fetchImpl);
  await assert.rejects(
    () => client.decideRelationship("nope", "confirmed"),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 404);
      assert.equal(error.message, "Unknown relationship: nope");
      return true;
    },
  );
  assert.equal(calls.length, 1);

  const messageOnly = stubFetch(() => jsonResponse({ message: "fingerprint must not be blank" }, 400));
  await assert.rejects(
    () => createClient(messageOnly.fetchImpl).acceptDrift("demo", ""),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 400);
      assert.equal(error.message, "fingerprint must not be blank");
      return true;
    },
  );
});

test("an error without a usable body falls back to the status text", async () => {
  const { fetchImpl } = stubFetch(
    () => new Response("<html>boom</html>", { status: 500, statusText: "Internal Server Error" }),
  );
  await assert.rejects(
    () => createClient(fetchImpl).getGraph(false),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 500);
      assert.equal(error.message, "Internal Server Error");
      return true;
    },
  );
});
