/**
 * CN-008: controller for the drift screen.
 *
 * Reads every source's drift report (GET /api/graph/drift) and shows one
 * ui-card per source: its status, when it was checked, and — for a changed
 * source — its changes grouped by severity (breaking, review, additive) with
 * the impact line under each. Change and impact wording lives in drift/model.ts;
 * this module only builds elements and wires events, and it never concatenates
 * server values into HTML (ui-text renders its content with textContent).
 *
 * Buttons:
 *  - "Check now" (header) runs a fresh check of every source with
 *    getDrift(true) and re-renders; the first load serves remembered reports
 *    with getDrift(false).
 *  - "Accept as baseline" (a changed card) asks window.confirm first and, only
 *    when confirmed, POSTs the report's currentFingerprint through
 *    acceptDrift, then re-checks with getDrift(true). A cancelled confirm
 *    sends nothing.
 *  - "Confirm rename" (each rename candidate) moves the overlay edits with
 *    confirmRename(source, from, to), then re-checks with getDrift(true).
 *
 * Report errors follow the labels screen: a failed getDrift is shown in the
 * screen error area, but an ApiError from an accept or rename stays on that
 * source's own card, so the message sits beside the action that produced it.
 * startDrift returns the same pending() accessor as the other screens: it
 * resolves once every started task (load, check, accept, rename) has settled.
 */

import { ApiError, type ApiClient } from "../api/client.js";
import type { DriftReport, SchemaChange } from "../api/types.js";
import {
  acceptMessage,
  canAccept,
  changeText,
  checkedText,
  groupChanges,
  impactText,
  renameCandidate,
  SEVERITY_LABELS,
  statusText,
  type RenameCandidate,
  type SeverityGroup,
} from "./model.js";

/** The pieces of a source card the action handlers need to reach after an await. */
interface CardRefs {
  card: HTMLElement;
  buttons: HTMLElement[];
  error: HTMLElement;
}

/**
 * Builds the drift screen inside container, fetching through client. Returns a
 * function resolving once all screen work started so far has finished.
 */
export function startDrift(
  container: HTMLElement,
  client: ApiClient,
): () => Promise<void> {
  const header = document.createElement("ui-card");
  header.setAttribute("title", "Drift");
  header.setAttribute(
    "description",
    "Review how each source's structure changed since its accepted baseline, carry edits across confirmed renames, then accept a new baseline.",
  );

  const check = document.createElement("ui-button");
  check.setAttribute("label", "Check now");
  check.setAttribute("data-action", "check");
  check.setAttribute("variant", "secondary");
  check.addEventListener("click", () => {
    // Never disabled on purpose, as on the other screens: clicking while a
    // request is stuck raises loadSeq and starts a superseding check.
    begin(() => load(true));
  });
  header.appendChild(check);

  const loading = document.createElement("ui-text");
  loading.setAttribute("content", "Checking each source for drift…");
  header.appendChild(loading);

  const errorArea = document.createElement("div");
  errorArea.className = "screen-error";
  errorArea.setAttribute("role", "alert");
  errorArea.hidden = true;

  const list = document.createElement("div");
  list.className = "drift-list";

  container.replaceChildren(header, errorArea, list);

  /** Screen work in flight (loads, accepts, renames); the queue drains to zero. */
  let inFlight = 0;
  /** Sequence of getDrift requests; only the newest response is allowed to render. */
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
    // load, runAction and its reload all catch everything, so work() never rejects here.
    void work().finally(() => {
      inFlight -= 1;
      if (inFlight === 0) {
        markDrained();
      }
    });
  }

  function showError(error: unknown): void {
    errorArea.textContent = messageFor(error);
    errorArea.hidden = false;
  }

  function clearError(): void {
    errorArea.textContent = "";
    errorArea.hidden = true;
  }

  /** An ApiError message verbatim; anything else a generic unreachable note. */
  function messageFor(error: unknown): string {
    return error instanceof ApiError
      ? error.message
      : "Request failed — check that the API is reachable.";
  }

  /**
   * Fetches the reports and renders them, remembering the reports the cards
   * were built from so an action reloads from a fresh check. check=true runs a
   * fresh check of every source first; false serves the remembered reports.
   */
  async function load(checkNow: boolean): Promise<void> {
    const seq = ++loadSeq;
    if (loading.parentNode !== header) {
      header.appendChild(loading);
    }
    try {
      const reports = await client.getDrift(checkNow);
      if (seq !== loadSeq) {
        return; // a newer load started meanwhile; its response renders instead
      }
      clearError();
      renderReports(reports);
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

  function renderReports(reports: DriftReport[]): void {
    if (reports.length === 0) {
      const empty = document.createElement("ui-text");
      empty.setAttribute("content", "No sources are configured, so there is nothing to check.");
      list.replaceChildren(empty);
      return;
    }
    list.replaceChildren(...reports.map(buildSourceCard));
  }

  /** One source as a card: status, checked time, its changes or its error. */
  function buildSourceCard(report: DriftReport): HTMLElement {
    const card = document.createElement("ui-card");
    card.setAttribute("data-source", report.source);
    card.setAttribute("title", report.source);

    const status = document.createElement("ui-text");
    status.setAttribute("data-drift", "status");
    status.setAttribute("content", statusText(report));
    card.appendChild(status);

    card.appendChild(textRow(checkedText(report)));

    const refs: CardRefs = { card, buttons: [], error: makeErrorArea() };

    if (report.status === "changed") {
      renderChanges(refs, report);
    }
    if (canAccept(report)) {
      const fingerprint = report.currentFingerprint ?? "";
      const accept = actionButton("Accept as baseline", "accept", "primary");
      accept.addEventListener("click", () => {
        if (accept.hasAttribute("disabled")) {
          return;
        }
        // Confirmed first: a decline sends nothing at all.
        if (!window.confirm(acceptMessage(report))) {
          return;
        }
        begin(() => runAction(refs, () => client.acceptDrift(report.source, fingerprint)));
      });
      refs.buttons.push(accept);
    }

    if (refs.buttons.length > 0) {
      const actions = document.createElement("div");
      actions.className = "drift-actions";
      actions.replaceChildren(...refs.buttons);
      card.appendChild(actions);
    }
    card.appendChild(refs.error);
    return card;
  }

  /** The changes grouped by severity, each with its impact line and rename button. */
  function renderChanges(refs: CardRefs, report: DriftReport): void {
    const groups = groupChanges(report);
    if (groups.length === 0) {
      return;
    }
    for (const group of groups) {
      refs.card.appendChild(severityHeading(group));
      for (const entry of group.changes) {
        refs.card.appendChild(textRow(changeText(entry.change)));
        const impact = impactText(entry.impact);
        if (impact !== "") {
          refs.card.appendChild(impactRow(impact));
        }
        addRenameButton(refs, report, entry.change);
      }
    }
  }

  /**
   * A Confirm rename button for a rename candidate; the {from, to} ids come
   * from the model. Non-candidate changes get nothing, and a candidate that
   * names no counterpart is skipped.
   */
  function addRenameButton(refs: CardRefs, report: DriftReport, change: SchemaChange): void {
    const candidate: RenameCandidate | null = renameCandidate(report, change);
    if (candidate === null) {
      return;
    }
    const { from, to } = candidate;
    const button = actionButton("Confirm rename", "rename", "secondary");
    button.setAttribute("data-rename-from", from);
    button.setAttribute("data-rename-to", to);
    button.addEventListener("click", () => {
      if (button.hasAttribute("disabled")) {
        return;
      }
      begin(() => runAction(refs, () => client.confirmRename(report.source, from, to)));
    });
    refs.buttons.push(button);
  }

  /**
   * Runs one card action: disable the card's buttons, send the request, then
   * re-check with getDrift(true) so the fresh report drives the re-render. A
   * failure is reported on this card, and the buttons are freed on the exact
   * nodes the user clicked — if the reload already replaced the card they are
   * detached and the calls are no-ops, exactly like the labels screen.
   */
  async function runAction(refs: CardRefs, action: () => Promise<unknown>): Promise<void> {
    clearError();
    setButtonsEnabled(refs.buttons, false);
    hideCardError(refs);
    try {
      await action();
      await load(true);
      restoreCardIfNotRerendered(refs);
    } catch (error) {
      showCardError(refs, error);
      setButtonsEnabled(refs.buttons, true);
    }
  }

  function makeErrorArea(): HTMLElement {
    const error = document.createElement("div");
    error.className = "screen-error drift-error";
    error.setAttribute("role", "alert");
    error.hidden = true;
    return error;
  }

  function showCardError(refs: CardRefs, error: unknown): void {
    if (!refs.error.isConnected) {
      // A newer load replaced this card while the action ran, so its error
      // area would show nothing; report through the visible screen banner.
      showError(error);
      return;
    }
    refs.error.textContent = messageFor(error);
    refs.error.hidden = false;
  }

  function hideCardError(refs: CardRefs): void {
    refs.error.textContent = "";
    refs.error.hidden = true;
  }

  /**
   * Frees a card's buttons when the post-action reload did not re-render it: a
   * load(true) replaces every card, so a still-connected card means the reload
   * was superseded and the write-time disabled state would otherwise stick.
   */
  function restoreCardIfNotRerendered(refs: CardRefs): void {
    if (!refs.card.isConnected) {
      return;
    }
    hideCardError(refs);
    setButtonsEnabled(refs.buttons, true);
  }

  function setButtonsEnabled(buttons: readonly HTMLElement[], enabled: boolean): void {
    for (const button of buttons) {
      if (enabled) {
        button.removeAttribute("disabled");
      } else {
        button.setAttribute("disabled", "");
      }
    }
  }

  function severityHeading(group: SeverityGroup): HTMLElement {
    const heading = document.createElement("div");
    heading.className = "drift-severity";
    heading.setAttribute("data-severity", group.severity);
    heading.textContent = SEVERITY_LABELS[group.severity];
    return heading;
  }

  function textRow(content: string): HTMLElement {
    const text = document.createElement("ui-text");
    text.setAttribute("content", content);
    return text;
  }

  function impactRow(content: string): HTMLElement {
    const text = document.createElement("ui-text");
    text.setAttribute("data-drift", "impact");
    text.setAttribute("content", content);
    return text;
  }

  function actionButton(label: string, action: string, variant: string): HTMLElement {
    const button = document.createElement("ui-button");
    button.setAttribute("label", label);
    button.setAttribute("data-action", action);
    button.setAttribute("variant", variant);
    return button;
  }

  begin(() => load(false));

  return () => drained;
}
