import test from "node:test";
import assert from "node:assert/strict";
import type { QueryOptions } from "../../api/client.js";
import type { ColumnCategory, DataPage, GraphColumn, GraphEntity } from "../../api/types.js";
import {
  displayRows,
  pageInfo,
  parsePageChange,
  parseSortChange,
  queryFor,
  tableColumns,
  PAGE_SIZE,
} from "../model.js";

function column(name: string, label: string | null = null): GraphColumn {
  const category: ColumnCategory = "TEXT";
  return {
    id: `demo:users.${name}`,
    name,
    type: "varchar(80)",
    category,
    nullable: false,
    label,
    description: null,
  };
}

function entity(columns: GraphColumn[]): GraphEntity {
  return {
    id: "demo:users",
    source: "demo",
    name: "users",
    kind: "table",
    label: null,
    description: null,
    keyColumns: ["id"],
    columns,
    baseTables: [],
  };
}

function page(
  count: number,
  total: number | null,
  offset: number | null,
): DataPage {
  const items: Record<string, unknown>[] = [];
  for (let index = 0; index < count; index += 1) {
    items.push({ id: index });
  }
  return { items, total, limit: PAGE_SIZE, offset };
}

test("tableColumns maps column names to fields with the label or the name as text", () => {
  const table = tableColumns(
    entity([column("id", "Identifier"), column("username"), column("created_at", "Created")]),
  );
  assert.deepEqual(table, [
    { field: "id", label: "Identifier" },
    { field: "username", label: "username" },
    { field: "created_at", label: "Created" },
  ]);
});

test("tableColumns of an entity without columns is empty", () => {
  assert.deepEqual(tableColumns(entity([])), []);
});

test("pageInfo reads Rows 51–100 of 340 for a middle page", () => {
  assert.equal(pageInfo(page(50, 340, 50)), "Rows 51\u2013100 of 340");
  assert.equal(pageInfo(page(50, 340, 0)), "Rows 1\u201350 of 340");
});

test("pageInfo drops the total when it is null", () => {
  assert.equal(pageInfo(page(50, null, 100)), "Rows 101\u2013150");
});

test("pageInfo reads a short last page by its real range", () => {
  assert.equal(pageInfo(page(40, 340, 300)), "Rows 301\u2013340 of 340");
});

test("pageInfo handles empty pages and a missing offset", () => {
  assert.equal(pageInfo(page(0, 340, 50)), "No rows");
  assert.equal(pageInfo(page(0, null, null)), "No rows");
  // offset null behaves like 0.
  assert.equal(pageInfo(page(2, 2, null)), "Rows 1\u20132 of 2");
});

test("displayRows turns objects and arrays into JSON text and keeps scalars", () => {
  const rows = displayRows([
    {
      id: 7,
      name: "Ann",
      active: true,
      score: null,
      tags: ["admin", "ops"],
      meta: { level: 3, nested: { ok: false } },
    },
  ]);
  assert.deepEqual(rows, [
    {
      id: 7,
      name: "Ann",
      active: true,
      score: null,
      tags: '["admin","ops"]',
      meta: '{"level":3,"nested":{"ok":false}}',
    },
  ]);
});

test("displayRows does not mutate the source rows", () => {
  const source: Record<string, unknown>[] = [{ tags: ["a"] }];
  const shaped = displayRows(source);
  assert.deepEqual(source[0].tags, ["a"], "the original array is untouched");
  assert.equal(shaped[0].tags, '["a"]');
});

test("parseSortChange takes the sort string and rejects other payloads", () => {
  assert.equal(parseSortChange({ sort: "-name" }), "-name");
  assert.equal(parseSortChange({ sort: "name" }), "name");
  assert.equal(parseSortChange(null), null);
  assert.equal(parseSortChange({ offset: 1 }), null);
  assert.equal(parseSortChange({ sort: 3 }), null);
});

test("parsePageChange takes a numeric offset and limit and rejects malformed details", () => {
  assert.deepEqual(parsePageChange({ offset: 50, limit: 50 }), { offset: 50, limit: 50 });
  assert.equal(parsePageChange(null), null);
  assert.equal(parsePageChange({ offset: "50", limit: 50 }), null);
  assert.equal(parsePageChange({ offset: -1, limit: 50 }), null);
  assert.equal(parsePageChange({ offset: 1.5, limit: 50 }), null);
  assert.equal(parsePageChange({ offset: 50 }), null);
});

test("queryFor fixes the page size, starts over at the given offset and omits a blank sort", () => {
  const expected: QueryOptions = { limit: 50, offset: 100 };
  assert.deepEqual(queryFor(100, ""), expected);
  assert.deepEqual(queryFor(0, "-id"), { limit: 50, offset: 0, sort: "-id" });
});
