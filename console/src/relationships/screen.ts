/**
 * CN-004: controller for the relationships screen.
 *
 * Loads the combined document with getGraph(true) — the rejected bucket is
 * only in the response when the flag is set — shapes it with the pure model
 * and renders it through view.ts. A card click disables that card's buttons,
 * calls the client and, on success, reloads the document and re-renders; on
 * failure it shows the ApiError message in the error area above the list.
 * Re-enabling always targets the exact button nodes captured at click time,
 * so a late failure can never re-enable a newer card that another decision is
 * running on, and a decision whose reload was superseded still frees its own
 * card. Every load re-shows the loading line; a load only renders when it is
 * still the newest request, so a late stale response cannot overwrite fresher
 * data. Refresh stays clickable: clicking it while a request is stuck starts a
 * newer load that supersedes it, which is the escape hatch for a hung request.
 * startRelationships returns a pending() accessor that resolves once every
 * started screen work has settled (the in-flight queue drains), which is what
 * the DOM tests await; the router ignores it.
 */

import { ApiError, type ApiClient } from "../api/client.js";
import { groupRelationships } from "./model.js";
import {
  renderRelationships,
  setButtonsEnabled,
  setRelationshipButtonsEnabled,
  type RelationshipHandlers,
} from "./view.js";

/**
 * Builds the relationships screen inside container, fetching through client.
 * Returns a function resolving once all screen work started so far (first
 * load, Refresh, or buttons' decide-and-reload) has finished rendering.
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
    // Never disabled on purpose: clicking while a request is stuck raises
    // loadSeq and starts a superseding load, so the screen can recover from
    // a hung request. Superseded responses are dropped by load's seq guard.
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

  /** Screen work in flight (loads and decisions); the queue drains to zero. */
  let inFlight = 0;
  /** Sequence of getGraph requests; only the newest response is allowed to render. */
  let loadSeq = 0;
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
    // load and runDecision catch everything, so work() never rejects here.
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
    const decided = setRelationshipButtonsEnabled(list, id, false);
    try {
      await action();
      await load();
    } catch (error) {
      showError(error);
    } finally {
      // Re-enable the exact nodes clicked, never a card re-queried by id: if
      // this decision's reload rendered (or a newer one did), these nodes are
      // detached and this is a no-op; if the reload failed or was discarded as
      // stale, the same card is still on screen and must not stay disabled.
      setButtonsEnabled(decided, true);
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

  return () => drained;
}
