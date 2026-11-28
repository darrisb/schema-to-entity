/**
 * CN-007: controller for the data browser screen.
 *
 * A ui-dropdown lists the entities of the combined document (same options as
 * the links and labels screens); picking one rebuilds a ui-table and loads
 * its first page with queryEntity(source, name, {limit: 50, offset: 0}).
 * The table itself drives paging and sorting: its page-size attribute is the
 * page the screen requests, a page-change event reloads the offset it names,
 * and a sort-change event reloads from offset 0 with the new sort string
 * ("field" / "-field", the same naming the data API takes). The screen never
 * mutates rows — it only writes the columns and data-bound-rows JSON
 * attributes and moves data-ds-status through loading, idle, empty or error.
 *
 * The component contract was verified against
 * node_modules/@visual-ui/components/dist/table/ui-table.js: sort-change
 * carries detail {sort} (toggleSort, which also mirrors the sort onto the
 * host sort attribute) and page-change carries detail {offset, limit},
 * while the page offset itself is kept only in the internal _offset field.
 * Because the offset has no readable attribute, the screen records the last
 * issued query (table, entity, offset, sort) in its own `view` variable and
 * the page-change handler takes the sort from there rather than reading a
 * host attribute; Refresh re-issues that recorded query so the rows on
 * screen follow a reloaded document.
 *
 * The table element is rebuilt on every entity pick on purpose: ui-table
 * tracks its requested offset internally, and a fresh element is the only
 * way to be sure the new entity starts at page one and no stale sort state
 * carries over. Row shaping (columns, page text, JSON cells for nested
 * values) lives in data/model.ts; this module only builds elements and wires
 * events.
 *
 * A query failure stays on the table: data-ds-status becomes "error" and
 * data-ds-error carries the ApiError message, which the component shows in
 * its own error part. Only the document load itself uses the screen error
 * area. Like the other screens, startData returns a pending() accessor that
 * resolves once every screen task started so far has finished rendering.
 */

import { ApiError, type ApiClient } from "../api/client.js";
import type { GraphEntity, SchemaGraph } from "../api/types.js";
import { entityOptions } from "../links/model.js";
import {
  displayRows,
  pageInfo,
  parsePageChange,
  parseSortChange,
  queryFor,
  tableColumns,
  PAGE_SIZE,
} from "./model.js";

/** ui-dropdown keeps its current value on the element; the screen reads it there. */
interface ValueElement extends HTMLElement {
  value: string;
}

/**
 * Builds the data screen inside container, fetching through client. Returns
 * a function resolving once all screen work started so far (load, entity
 * picks, page or sort changes) has finished rendering.
 */
export function startData(
  container: HTMLElement,
  client: ApiClient,
): () => Promise<void> {
  const header = document.createElement("ui-card");
  header.setAttribute("title", "Data");
  header.setAttribute(
    "description",
    "Browse the rows of one entity through the read-only data API; page and sort with the table's own controls.",
  );

  const refresh = document.createElement("ui-button");
  refresh.setAttribute("label", "Refresh");
  refresh.setAttribute("data-action", "refresh");
  refresh.setAttribute("variant", "secondary");
  refresh.addEventListener("click", () => {
    // Never disabled on purpose, as on the other screens: clicking it while a
    // request is stuck raises loadSeq and starts a superseding load.
    begin(load);
  });
  header.appendChild(refresh);

  const loading = document.createElement("ui-text");
  loading.setAttribute("content", "Loading the schema document…");
  header.appendChild(loading);

  const errorArea = document.createElement("div");
  errorArea.className = "screen-error";
  errorArea.setAttribute("role", "alert");
  errorArea.hidden = true;

  const picker = document.createElement("ui-card");
  picker.setAttribute("title", "Entity");
  const entityDropdown = document.createElement("ui-dropdown") as ValueElement;
  entityDropdown.setAttribute("label", "Entity");
  entityDropdown.setAttribute("placeholder", "Choose an entity…");
  entityDropdown.setAttribute("data-dropdown", "entity");
  entityDropdown.setAttribute("options", "[]");
  entityDropdown.addEventListener("change", () => {
    const entity =
      graph === null
        ? undefined
        : graph.entities.find((candidate) => candidate.id === entityDropdown.value);
    if (entity === undefined) {
      showHint();
      return;
    }
    showEntity(entity);
  });
  picker.replaceChildren(entityDropdown);

  const results = document.createElement("div");
  results.className = "data-results";

  /** Survives entity picks: it always sits above the table and shows the page position. */
  const pageText = document.createElement("ui-text");
  pageText.setAttribute("data-info", "page");
  pageText.setAttribute("content", "");

  /**
   * The table on screen and the last query issued for it, or null while the
   * hint shows. Set by showEntity and by both table handlers; read by Refresh
   * to reload the visible page. Declared before the first showHint() call.
   */
  let view: { table: HTMLElement; entity: GraphEntity; offset: number; sort: string } | null =
    null;

  container.replaceChildren(header, errorArea, picker, results);
  showHint();

  /** The newest document the dropdown was filled from; null until it loads. */
  let graph: SchemaGraph | null = null;
  /** Screen work in flight (loads, queries); the queue drains to zero. */
  let inFlight = 0;
  /** Sequence of getGraph requests; only the newest response is allowed to render. */
  let loadSeq = 0;
  /** Sequence of queryEntity requests; only the newest response may touch the table. */
  let querySeq = 0;
  /** Resolves when the in-flight queue drains; replaced whenever work starts. */
  let drained: Promise<void> = Promise.resolve();
  let markDrained: () => void = () => {};

  function begin(work: () => Promise<void>): void {
    inFlight += 1;
    if (inFlight === 1) {
      drained = new Promise<void>((resolve) => {
        markDrained = resolve;
      });
    }
    // load and runQuery both catch everything, so work() never rejects here.
    void work().finally(() => {
      inFlight -= 1;
      if (inFlight === 0) {
        markDrained();
      }
    });
  }

  function showError(error: unknown): void {
    errorArea.textContent =
      error instanceof ApiError
        ? error.message
        : "Request failed — check that the API is reachable.";
    errorArea.hidden = false;
  }

  function clearError(): void {
    errorArea.textContent = "";
    errorArea.hidden = true;
  }

  function showHint(): void {
    view = null;
    const hint = document.createElement("ui-text");
    hint.setAttribute("content", "Choose an entity to browse its rows.");
    pageText.setAttribute("content", "");
    results.replaceChildren(hint);
  }

  /** One query: status through loading, then idle/empty with rows and page text, or error. */
  async function runQuery(table: HTMLElement, entity: GraphEntity, offset: number, sort: string): Promise<void> {
    const seq = ++querySeq;
    table.setAttribute("data-ds-status", "loading");
    table.removeAttribute("data-ds-error");
    try {
      const page = await client.queryEntity(entity.source, entity.name, queryFor(offset, sort));
      if (seq !== querySeq) {
        return; // a newer pick or page request owns the table now
      }
      table.setAttribute("data-bound-rows", JSON.stringify(displayRows(page.items)));
      table.setAttribute("data-ds-status", page.items.length === 0 ? "empty" : "idle");
      pageText.setAttribute("content", pageInfo(page));
    } catch (error) {
      if (seq !== querySeq) {
        return;
      }
      // The component keeps previously bound rows visible under its error part;
      // the failure belongs to the table, so it is reported there, not in the
      // screen banner.
      table.setAttribute("data-ds-status", "error");
      table.setAttribute(
        "data-ds-error",
        error instanceof ApiError
          ? error.message
          : "Request failed — check that the API is reachable.",
      );
      pageText.setAttribute("content", "");
    }
  }

  /**
   * A fresh table for one entity: the declared columns are known from the
   * document before any rows arrive, and the new element starts with the
   * component's internal offset at 0 and no sort attribute.
   */
  function showEntity(entity: GraphEntity): void {
    const table = document.createElement("ui-table");
    table.setAttribute("page-size", String(PAGE_SIZE));
    table.setAttribute("data-table", "rows");
    table.setAttribute("columns", JSON.stringify(tableColumns(entity)));
    table.setAttribute("data-ds-status", "loading");

    table.addEventListener("sort-change", (event: Event) => {
      const sort = parseSortChange((event as CustomEvent<unknown>).detail);
      if (sort === null) {
        return;
      }
      if (view === null || view.table !== table) {
        return; // an event from a table this screen already replaced
      }
      // A new order starts over: the component resets its own offset and the
      // screen records the sort the event names, from the verified detail
      // {sort}, rather than reading a host attribute back.
      view = { table, entity, offset: 0, sort };
      begin(() => runQuery(table, entity, 0, sort));
    });
    table.addEventListener("page-change", (event: Event) => {
      const position = parsePageChange((event as CustomEvent<unknown>).detail);
      if (position === null) {
        return;
      }
      if (view === null || view.table !== table) {
        return; // an event from a table this screen already replaced
      }
      // The table already advanced its internal offset; fetch exactly the
      // page the event names (detail {offset, limit}) under the sort this
      // screen last recorded.
      const sort = view.sort;
      view = { table, entity, offset: position.offset, sort };
      begin(() => runQuery(table, entity, position.offset, sort));
    });

    results.replaceChildren(pageText, table);
    pageText.setAttribute("content", "");
    view = { table, entity, offset: 0, sort: "" };
    begin(() => runQuery(table, entity, 0, ""));
  }

  async function load(): Promise<void> {
    const seq = ++loadSeq;
    if (loading.parentNode !== header) {
      // Re-show the indicator: the first load removed it again below.
      header.appendChild(loading);
    }
    try {
      // includeRejected=true, as on the other screens: entity browsing is
      // unrelated to relationship decisions and must not hide an entity.
      const fresh = await client.getGraph(true);
      if (seq !== loadSeq) {
        return; // a newer load started meanwhile; its response renders instead
      }
      clearError();
      graph = fresh;
      const options = entityOptions(fresh);
      entityDropdown.setAttribute("options", JSON.stringify(options));
      if (
        entityDropdown.value !== "" &&
        !options.some((option) => option.value === entityDropdown.value)
      ) {
        // The picked entity is gone from the document: clear the stale choice
        // and take its table off screen, as the labels screen does with rows.
        entityDropdown.value = "";
        showHint();
      } else if (view !== null) {
        // A table is on screen: re-issue the recorded query, at the page and
        // order currently shown, so Refresh updates the rows the user is
        // looking at and not only the dropdown's document.
        await runQuery(view.table, view.entity, view.offset, view.sort);
      }
    } catch (error) {
      if (seq === loadSeq) {
        showError(error);
      }
    } finally {
      if (seq === loadSeq && loading.parentNode === header) {
        header.removeChild(loading);
      }
    }
  }

  begin(load);

  return () => drained;
}
