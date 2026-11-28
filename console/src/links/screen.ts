/**
 * CN-005: controller for the links screen.
 *
 * Loads the combined document and offers two things: a form that asserts a
 * manual relationship between two columns, and the list of manual links with
 * Remove buttons. The four ui-dropdowns are wired the way the library drives
 * them — options arrive as a JSON attribute, the current choice reads back off
 * the element's value property after its bubbling change event. Choosing an
 * entity refills that side's column dropdown and drops the column chosen from
 * the previous entity, because a column id only means something under the
 * entity it belongs to.
 *
 * Create validates the pair locally first (links/model.ts): a missing side or
 * a same-column link shows the form message and sends no request. A request
 * that does go out and fails shows the server's ApiError message in the screen
 * error area instead. A success reloads the document — the new manual link
 * appears in the list — and Create additionally resets the form; Remove
 * reloads but keeps the current selections so several links can be built
 * without re-picking the same entities. startLinks returns the same pending()
 * accessor as startRelationships: it resolves once every screen task started
 * so far has finished rendering, which is what the DOM tests await.
 */

import { ApiError, type ApiClient } from "../api/client.js";
import type { GraphRelationship, SchemaGraph } from "../api/types.js";
import { summary } from "../relationships/model.js";
import {
  columnOptions,
  entityOptions,
  manualRelationships,
  validateLink,
  type DropdownOption,
} from "./model.js";

/**
 * How the console drives ui-dropdown: the library keeps the chosen option's
 * value on the element itself, so the screen reads and clears selections
 * through this property instead of reaching into the shadow DOM.
 */
interface DropdownElement extends HTMLElement {
  value: string;
}

/**
 * Builds the links screen inside container, fetching through client. Returns
 * a function resolving once all screen work started so far (load, Create or
 * a Remove) has finished rendering.
 */
export function startLinks(
  container: HTMLElement,
  client: ApiClient,
): () => Promise<void> {
  const header = document.createElement("ui-card");
  header.setAttribute("title", "Links");
  header.setAttribute(
    "description",
    "Assert a manual relationship between two columns, in the same source or across sources.",
  );

  const refresh = document.createElement("ui-button");
  refresh.setAttribute("label", "Refresh");
  refresh.setAttribute("data-action", "refresh");
  refresh.setAttribute("variant", "secondary");
  refresh.addEventListener("click", () => {
    // Never disabled on purpose, as on the relationships screen: clicking it
    // while a request is stuck raises loadSeq and starts a superseding load.
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

  const formCard = document.createElement("ui-card");
  formCard.setAttribute("title", "Create a link");
  formCard.setAttribute(
    "description",
    "Pick a column on one entity and a column on another, then create the manual relationship.",
  );

  const fields = document.createElement("div");
  fields.className = "link-form-fields";
  const fromEntity = createDropdown("From entity", "Choose an entity…", "from-entity");
  const fromColumn = createDropdown("From column", "Choose a column…", "from-column");
  const toEntity = createDropdown("To entity", "Choose an entity…", "to-entity");
  const toColumn = createDropdown("To column", "Choose a column…", "to-column");
  fields.replaceChildren(fromEntity, fromColumn, toEntity, toColumn);

  const actions = document.createElement("div");
  actions.className = "link-form-actions";
  const create = document.createElement("ui-button");
  create.setAttribute("label", "Create link");
  create.setAttribute("data-action", "create");
  create.setAttribute("variant", "primary");
  create.addEventListener("click", () => {
    // ui-button's disabled only blocks its inner button, so guard here too.
    if (create.hasAttribute("disabled")) {
      return;
    }
    begin(createLink);
  });
  actions.appendChild(create);

  const formError = document.createElement("div");
  formError.className = "screen-error link-form-error";
  formError.setAttribute("role", "alert");
  formError.hidden = true;

  formCard.replaceChildren(fields, actions, formError);

  const list = document.createElement("div");
  list.className = "links-list";

  container.replaceChildren(header, errorArea, formCard, list);

  /** The newest document the dropdowns were filled from; null until it loads. */
  let graph: SchemaGraph | null = null;
  /** Screen work in flight (loads, creates, removals); the queue drains to zero. */
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
    // load, createLink and removeLink all catch everything, so work() never
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

  function showFormError(message: string): void {
    formError.textContent = message;
    formError.hidden = false;
  }

  function clearFormError(): void {
    formError.textContent = "";
    formError.hidden = true;
  }

  function createDropdown(label: string, placeholder: string, name: string): DropdownElement {
    const dropdown = document.createElement("ui-dropdown") as DropdownElement;
    dropdown.setAttribute("label", label);
    dropdown.setAttribute("placeholder", placeholder);
    dropdown.setAttribute("data-dropdown", name);
    setOptions(dropdown, []);
    return dropdown;
  }

  function setOptions(dropdown: DropdownElement, options: DropdownOption[]): void {
    dropdown.setAttribute("options", JSON.stringify(options));
  }

  /**
   * Wires one entity dropdown to its column dropdown: any new entity choice
   * fills the columns of exactly that entity and drops the column picked from
   * the previous one, so a stale pair can never be submitted.
   */
  function wireEntityChoice(entityDropdown: DropdownElement, columnDropdown: DropdownElement): void {
    entityDropdown.addEventListener("change", () => {
      clearFormError();
      columnDropdown.value = "";
      setOptions(
        columnDropdown,
        graph === null ? [] : columnOptions(graph, entityDropdown.value),
      );
    });
    columnDropdown.addEventListener("change", clearFormError);
  }

  wireEntityChoice(fromEntity, fromColumn);
  wireEntityChoice(toEntity, toColumn);

  async function load(): Promise<void> {
    const seq = ++loadSeq;
    if (loading.parentNode !== header) {
      // Re-show the indicator: the first load removed it again below.
      header.appendChild(loading);
    }
    try {
      // includeRejected=true so a manual link a reviewer rejected is still
      // listed here, where Remove is the way to take it back off.
      const fresh = await client.getGraph(true);
      if (seq !== loadSeq) {
        return; // a newer load started meanwhile; its response renders instead
      }
      clearError();
      graph = fresh;
      setOptions(fromEntity, entityOptions(fresh));
      setOptions(toEntity, entityOptions(fresh));
      renderManualList(fresh);
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

  /** Back to an empty form: nothing chosen, column lists await an entity. */
  function resetForm(): void {
    for (const dropdown of [fromEntity, fromColumn, toEntity, toColumn]) {
      dropdown.value = "";
    }
    setOptions(fromColumn, []);
    setOptions(toColumn, []);
    clearFormError();
  }

  async function createLink(): Promise<void> {
    clearError();
    // Read the choice once: the same pair is validated, submitted and shown.
    const from = fromColumn.value;
    const to = toColumn.value;
    const message = validateLink(from, to);
    if (message !== null) {
      // The pair is not submittable: show why, and send nothing.
      showFormError(message);
      return;
    }
    clearFormError();
    create.setAttribute("disabled", "");
    try {
      await client.addManualRelationship(from, to);
      await load();
      resetForm();
    } catch (error) {
      showError(error);
    } finally {
      create.removeAttribute("disabled");
    }
  }

  async function removeLink(id: string, button: HTMLElement): Promise<void> {
    clearError();
    button.setAttribute("disabled", "");
    try {
      await client.removeRelationship(id);
      await load();
    } catch (error) {
      showError(error);
    } finally {
      // If the reload rendered, this button's card was replaced and the call
      // is a no-op on the detached node; if it failed, the card stays live and
      // must not stay disabled.
      button.removeAttribute("disabled");
    }
  }

  function renderManualList(fresh: SchemaGraph): void {
    const items = manualRelationships(fresh);

    const heading = document.createElement("h2");
    heading.className = "section-heading";
    heading.textContent = `Manual links (${String(items.length)})`;

    const children: HTMLElement[] = [heading];
    if (items.length === 0) {
      const empty = document.createElement("ui-text");
      empty.setAttribute("content", "No manual links yet — create one above.");
      children.push(empty);
    }
    for (const relationship of items) {
      children.push(buildManualCard(relationship, fresh));
    }
    list.replaceChildren(...children);
  }

  /** One manual link as a card: the summary line from the relationships model, Remove. */
  function buildManualCard(relationship: GraphRelationship, fresh: SchemaGraph): HTMLElement {
    const card = document.createElement("ui-card");
    card.setAttribute("data-relationship-id", relationship.id);
    card.setAttribute("title", summary(relationship, fresh));

    const remove = document.createElement("ui-button");
    remove.setAttribute("label", "Remove");
    remove.setAttribute("data-action", "remove");
    remove.setAttribute("variant", "ghost");
    remove.addEventListener("click", () => {
      if (remove.hasAttribute("disabled")) {
        return;
      }
      begin(() => removeLink(relationship.id, remove));
    });

    const cardActions = document.createElement("div");
    cardActions.className = "relationship-actions";
    cardActions.appendChild(remove);
    card.appendChild(cardActions);
    return card;
  }

  begin(load);

  return () => drained;
}
