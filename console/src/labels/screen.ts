/**
 * CN-006: controller for the labels screen.
 *
 * A ui-dropdown picks an entity (options shaped by links/model.ts, like the
 * links screen); the screen then renders one row per entity-plus-columns of
 * the selection, each a ui-card with Label and Description ui-inputs
 * prefilled from the document and a Save/Clear pair. labels/model.ts holds
 * the row shaping, the dirty rule and the save payload; this module only
 * builds elements and wires events.
 *
 * Save is disabled until a field differs from its prefill. A successful
 * setLabel or removeLabel reloads the document and re-renders: the rows come
 * back prefilled with the stored values (Save disabled again, row errors gone)
 * and the entity dropdown keeps its choice, because ui-dropdown restores its
 * value across an options update that still carries it. If the reload shows
 * the chosen entity is gone, the selection is cleared explicitly — ui-dropdown
 * would otherwise keep showing an id nothing renders against. Because that
 * reload rebuilds every row from the document, edits typed into a row that
 * was not saved are discarded: the screen works one row at a time. If the
 * reload request itself fails, its error goes to the screen banner and the
 * rows stay as typed — the written row's buttons are then freed again by the
 * dirty rule, so a succeeded write never leaves them locked.
 *
 * A failing request leaves the rows as typed and shows the ApiError message
 * in the row's own error area, so the message sits next to the row that
 * produced it; only when another row's reload has already replaced that card
 * does the message fall back to the screen error area, since a detached row
 * error would show nothing. Load failures use the screen error area.
 * startLabels returns the same pending() accessor as the other screens:
 * it resolves once every screen task started so far has finished rendering.
 */

import { ApiError, type ApiClient } from "../api/client.js";
import type { SchemaGraph } from "../api/types.js";
import { entityOptions } from "../links/model.js";
import { isDirty, labelChange, labelRows, type LabelRow, type LabelValues } from "./model.js";

/**
 * How the console drives ui-dropdown and ui-input: the library keeps the
 * current value on the element itself, so the screen reads selections and
 * field contents through this property.
 */
interface ValueElement extends HTMLElement {
  value: string;
}

/** The editable parts of one rendered row, captured at build time. */
interface RowRefs {
  labelInput: ValueElement;
  descriptionInput: ValueElement;
  save: HTMLElement;
  clear: HTMLElement;
  error: HTMLElement;
  /** The prefilled values the dirty rule compares against. */
  baseline: LabelValues;
}

/**
 * Builds the labels screen inside container, fetching through client. Returns
 * a function resolving once all screen work started so far (load, entity
 * picks, saves or clears) has finished rendering.
 */
export function startLabels(
  container: HTMLElement,
  client: ApiClient,
): () => Promise<void> {
  const header = document.createElement("ui-card");
  header.setAttribute("title", "Labels");
  header.setAttribute(
    "description",
    "Give the entity and each of its columns a human label and description; they feed the schema summary and the generated pages.",
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
    clearError();
    // A new pick needs no fetch: the document is already loaded, and
    // renderRows refills the rows from its current label values.
    renderRows();
  });
  picker.replaceChildren(entityDropdown);

  const rowsArea = document.createElement("div");
  rowsArea.className = "labels-rows";

  container.replaceChildren(header, errorArea, picker, rowsArea);

  /** The newest document the dropdown and rows were filled from; null until it loads. */
  let graph: SchemaGraph | null = null;
  /** Screen work in flight (loads, saves, clears); the queue drains to zero. */
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
    // load, saveRow and clearRow all catch everything, so work() never
    // rejects here.
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

  function showRowError(refs: RowRefs, error: unknown): void {
    if (!refs.error.isConnected) {
      // Another row's successful save re-rendered the rows while this write
      // was in flight, so the card this error belongs to is detached and
      // would show nothing; report through the visible screen banner instead.
      showError(error);
      return;
    }
    refs.error.textContent =
      error instanceof ApiError
        ? error.message
        : "Request failed — check that the API is reachable.";
    refs.error.hidden = false;
  }

  function hideRowError(refs: RowRefs): void {
    refs.error.textContent = "";
    refs.error.hidden = true;
  }

  async function load(): Promise<void> {
    const seq = ++loadSeq;
    if (loading.parentNode !== header) {
      // Re-show the indicator: the first load removed it again below.
      header.appendChild(loading);
    }
    try {
      // includeRejected=true, as on the other screens: the label rows read
      // entities only, and a rejected relationship must not hide an entity.
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
        // The chosen entity is gone from the document. Clearing the value is
        // what keeps the displayed choice honest: ui-dropdown would otherwise
        // restore the stale id into the re-rendered select.
        entityDropdown.value = "";
      }
      renderRows();
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

  /** Rebuilds every row of the current selection from the current document. */
  function renderRows(): void {
    const entityId = entityDropdown.value;
    if (graph === null || entityId === "") {
      const hint = document.createElement("ui-text");
      hint.setAttribute("content", "Choose an entity to edit its label and its columns' labels.");
      rowsArea.replaceChildren(hint);
      return;
    }
    rowsArea.replaceChildren(...labelRows(graph, entityId).map(buildRow));
  }

  function createInput(label: string, placeholder: string, value: string): ValueElement {
    const input = document.createElement("ui-input") as ValueElement;
    input.setAttribute("label", label);
    input.setAttribute("placeholder", placeholder);
    // default-value is what ui-input applies while no explicit value was set;
    // the screen never assigns .value, so the field opens showing the document's text.
    input.setAttribute("default-value", value);
    return input;
  }

  function currentValues(refs: RowRefs): LabelValues {
    return { label: refs.labelInput.value, description: refs.descriptionInput.value };
  }

  /** Save follows the dirty rule: enabled only while a field differs from its prefill. */
  function applySaveState(refs: RowRefs): void {
    if (isDirty(refs.baseline, currentValues(refs))) {
      refs.save.removeAttribute("disabled");
    } else {
      refs.save.setAttribute("disabled", "");
    }
  }

  function setRowButtonsEnabled(refs: RowRefs, enabled: boolean): void {
    for (const button of [refs.save, refs.clear]) {
      if (enabled) {
        button.removeAttribute("disabled");
      } else {
        button.setAttribute("disabled", "");
      }
    }
  }

  /**
   * Frees a row's buttons when the post-write reload did not re-render: a
   * successful load replaces this card (and its detached buttons) with a
   * freshly built one, so a still-connected card means the reload failed or
   * was superseded and the write-time disabled state would otherwise stick.
   * The dirty rule then decides what Save shows, exactly as on a failed write.
   * Reaching here means the write itself succeeded, so an error the row shows
   * from an earlier failed attempt is stale — and nothing else clears it,
   * because renderRows() did not run and no field was retyped.
   */
  function restoreRowIfNotRerendered(refs: RowRefs): void {
    if (!refs.save.isConnected) {
      return;
    }
    hideRowError(refs);
    setRowButtonsEnabled(refs, true);
    applySaveState(refs);
  }

  /** One row: heading, the two prefilled inputs, Save (dirty-gated) and Clear. */
  function buildRow(row: LabelRow): HTMLElement {
    const card = document.createElement("ui-card");
    card.setAttribute("data-label-id", row.id);
    card.setAttribute("title", row.heading);

    const labelInput = createInput("Label", "Human-readable name", row.label);
    labelInput.setAttribute("data-field", "label");
    const descriptionInput = createInput(
      "Description",
      "What this is, for the schema summary and generated pages",
      row.description,
    );
    descriptionInput.setAttribute("data-field", "description");

    const fields = document.createElement("div");
    fields.className = "label-row-fields";
    fields.replaceChildren(labelInput, descriptionInput);

    const save = document.createElement("ui-button");
    save.setAttribute("label", "Save");
    save.setAttribute("data-action", "save");
    save.setAttribute("variant", "primary");
    save.setAttribute("disabled", "");

    const clear = document.createElement("ui-button");
    clear.setAttribute("label", "Clear");
    clear.setAttribute("data-action", "clear");
    clear.setAttribute("variant", "ghost");

    const error = document.createElement("div");
    error.className = "screen-error row-error";
    error.setAttribute("role", "alert");
    error.hidden = true;

    const refs: RowRefs = {
      labelInput,
      descriptionInput,
      save,
      clear,
      error,
      baseline: { label: row.label, description: row.description },
    };

    const onEdit = (): void => {
      // Retyping makes an earlier row failure stale.
      hideRowError(refs);
      applySaveState(refs);
    };
    labelInput.addEventListener("input", onEdit);
    labelInput.addEventListener("change", onEdit);
    descriptionInput.addEventListener("input", onEdit);
    descriptionInput.addEventListener("change", onEdit);

    save.addEventListener("click", () => {
      // ui-button's disabled only blocks its inner button, so guard here too.
      if (save.hasAttribute("disabled")) {
        return;
      }
      begin(() => saveRow(row.id, refs));
    });
    clear.addEventListener("click", () => {
      if (clear.hasAttribute("disabled")) {
        return;
      }
      begin(() => clearRow(row.id, refs));
    });

    const actions = document.createElement("div");
    actions.className = "label-row-actions";
    actions.replaceChildren(save, clear);

    card.replaceChildren(fields, actions, error);
    return card;
  }

  async function saveRow(id: string, refs: RowRefs): Promise<void> {
    clearError();
    const change = labelChange(currentValues(refs));
    setRowButtonsEnabled(refs, false);
    try {
      await client.setLabel(id, change.label, change.description);
      await load();
      // load() swallows its own failures, so a thrown error is not the only
      // way the rows can survive the reload unchanged.
      restoreRowIfNotRerendered(refs);
    } catch (error) {
      // The rows stay exactly as typed: only this row's card reports the
      // failure, and Save returns to whatever the dirty rule now says.
      showRowError(refs, error);
      setRowButtonsEnabled(refs, true);
      applySaveState(refs);
    }
  }

  async function clearRow(id: string, refs: RowRefs): Promise<void> {
    clearError();
    setRowButtonsEnabled(refs, false);
    try {
      await client.removeLabel(id);
      await load();
      restoreRowIfNotRerendered(refs);
    } catch (error) {
      showRowError(refs, error);
      setRowButtonsEnabled(refs, true);
      applySaveState(refs);
    }
  }

  begin(load);

  return () => drained;
}
