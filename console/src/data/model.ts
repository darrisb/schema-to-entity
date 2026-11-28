/**
 * CN-007: pure view model for the data browser screen.
 *
 * Shapes the graph entity and the paged rows into what ui-table consumes
 * (a columns JSON attribute and data-bound-rows JSON), plus the page-position
 * text beside the table. The module touches no DOM and calls no API, so the
 * screen stays thin and this logic is testable under plain node --test.
 *
 * ui-table renders every cell with String(value), which would show
 * "[object Object]" for a nested value; displayRows therefore converts
 * objects and arrays to JSON text before the rows are bound. Nulls and
 * undefined stay as they are, because the component already shows those as
 * an empty cell.
 */

import type { QueryOptions } from "../api/client.js";
import type { DataPage, GraphEntity } from "../api/types.js";

/** Rows fetched per page; also written to the table's page-size attribute. */
export const PAGE_SIZE = 50;

/** One ui-table column entry: the row key to read and the header text. */
export interface TableColumn {
  field: string;
  label: string;
}

/** The {offset, limit} detail of a page-change event. */
export interface PagePosition {
  offset: number;
  limit: number;
}

/**
 * The entity's columns as table columns, in discovery order: the field is
 * the row key the data API returns (the column name), the header shows the
 * overlay label when the document carries one, otherwise the name — the same
 * label rule the other screens use.
 */
export function tableColumns(entity: GraphEntity): TableColumn[] {
  return entity.columns.map((column) => ({
    field: column.name,
    label: column.label ?? column.name,
  }));
}

/**
 * The position text for one page, e.g. "Rows 51–100 of 340". A null offset
 * counts as 0, a null total (the caller sent total=false) drops the "of …"
 * part, and an empty page reads "No rows" rather than a backwards range.
 */
export function pageInfo(page: DataPage): string {
  if (page.items.length === 0) {
    return "No rows";
  }
  const from = page.offset ?? 0;
  const start = from + 1;
  const end = from + page.items.length;
  const range = `Rows ${String(start)}\u2013${String(end)}`;
  return page.total === null ? range : `${range} of ${String(page.total)}`;
}

/**
 * The rows for data-bound-rows: scalar values pass through unchanged, and
 * objects and arrays become JSON text so the table's String()-based cells
 * show the value instead of "[object Object]". The input rows are not
 * mutated.
 */
export function displayRows(items: Record<string, unknown>[]): Record<string, unknown>[] {
  return items.map((row) => {
    const shaped: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(row)) {
      shaped[key] = typeof value === "object" && value !== null ? JSON.stringify(value) : value;
    }
    return shaped;
  });
}

/**
 * The sort string from a sort-change event's detail ({sort}), or null when
 * the payload is not one. A screen must ignore a malformed detail rather
 * than reload with a guessed sort.
 */
export function parseSortChange(detail: unknown): string | null {
  if (typeof detail !== "object" || detail === null) {
    return null;
  }
  const sort = (detail as Record<string, unknown>).sort;
  return typeof sort === "string" ? sort : null;
}

/**
 * The position from a page-change event's detail ({offset, limit}), or null
 * when the payload is malformed — offset must be a non-negative integer.
 */
export function parsePageChange(detail: unknown): PagePosition | null {
  if (typeof detail !== "object" || detail === null) {
    return null;
  }
  const fields = detail as Record<string, unknown>;
  const { offset, limit } = fields;
  if (typeof offset !== "number" || typeof limit !== "number") {
    return null;
  }
  if (!Number.isInteger(offset) || offset < 0) {
    return null;
  }
  return { offset, limit };
}

/** The options for one screen-issued query: fixed page size, given offset and sort. */
export function queryFor(offset: number, sort: string): QueryOptions {
  const options: QueryOptions = { limit: PAGE_SIZE, offset };
  if (sort !== "") {
    options.sort = sort;
  }
  return options;
}
