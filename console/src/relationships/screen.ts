/**
 * CN-004: controller for the relationships screen.
 *
 * Loads the combined document with getGraph(true) — the rejected bucket is
 * only in the response when the flag is set — shapes it with the pure model
 * and renders it through view.ts. A card click disables that card's buttons,
 * calls the client and, on success, reloads the document and re-renders; on
 * failure it shows the ApiError message in the error area above the list and
 * re-enables the buttons. While any screen work is in flight the Refresh
 * button is disabled (and its listener ignores clicks), and every load
 * re-shows the loading line; a load only renders when it is still the newest
 * request, so a late stale response cannot overwrite fresher data.
 * startRelationships returns a pending() accessor that resolves when the
 * initial load or the most recent click's work has settled, which is what
 * the DOM tests await; the router ignores it.
 */

import { ApiError, type ApiClient } from "../api/client.js";
import { groupRelationships } from "./model.js";
import {
  renderRelationships,
  setRelationshipButtonsEnabled,
  type RelationshipHandlers,
} from "./view.js";

/**
 * Builds the relationships screen inside container, fetching through client.
 * Returns a function resolving once the latest screen work (first load,
 * Refresh, or a button's decide-and-reload) has finished rendering.
 */
export function startRelationships(
  container: HTMLElement,
  client: ApiClient,
): () => Promise<void> {
  const header = document.createElement("ui-card");
  header.setAttribute("title", "Relationships");
  header.setAttribute(
    "description",
    "Confirm or reject proposed relationships; Undo clears a stored decision.",
  );

  const refresh = document.createElement("ui-button");
  refresh.setAttribute("label", "Refresh");
  refresh.setAttribute("data-action", "refresh");
  refresh.setAttribute("variant", "secondary");
  refresh.addEventListener("click", () => {
    // A click that reaches the disabled host must not start a second load.
    if (refresh.hasAttribute("disabled")) {
      return;
    }
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

  const list = document.createElement("div");
  list.className = "relationships-list";

  container.replaceChildren(header, errorArea, list);

  /** The most recent async work; load and runDecision catch, so it never rejects. */
  let current: Promise<void> = Promise.resolve();
  /** Screen work in flight (loads and decisions); Refresh stays disabled until it drains. */
  let inFlight = 0;
  /** Sequence of getGraph requests; only the newest response is allowed to render. */
  let loadSeq = 0;

  function begin(work: () => Promise<void>): void {
    inFlight += 1;
    refresh.setAttribute("disabled", "");
    current = work().finally(() => {
      inFlight -= 1;
      if (inFlight === 0) {
        refresh.removeAttribute("disabled");
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

  async function load(): Promise<void> {
    const seq = ++loadSeq;
    if (loading.parentNode !== header) {
      // Re-show the indicator: the first load removed it again below.
      header.appendChild(loading);
    }
    try {
      const graph = await client.getGraph(true);
      if (seq !== loadSeq) {
        return; // a newer load started meanwhile; its response renders instead
      }
      clearError();
      renderRelationships(list, groupRelationships(graph), graph, handlers);
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

  async function runDecision(id: string, action: () => Promise<void>): Promise<void> {
    clearError();
    setRelationshipButtonsEnabled(list, id, false);
    try {
      await action();
      await load();
    } catch (error) {
      showError(error);
      setRelationshipButtonsEnabled(list, id, true);
    }
  }

  const handlers: RelationshipHandlers = {
    onConfirm: (id) => {
      begin(() => runDecision(id, () => client.decideRelationship(id, "confirmed")));
    },
    onReject: (id) => {
      begin(() => runDecision(id, () => client.decideRelationship(id, "rejected")));
    },
    onUndo: (id) => {
      // Undo stores no decision at all: status null returns a proposal unreviewed.
      begin(() => runDecision(id, () => client.decideRelationship(id, null)));
    },
    onRemove: (id) => {
      begin(() => runDecision(id, () => client.removeRelationship(id)));
    },
  };

  begin(load);

  return () => current;
}
