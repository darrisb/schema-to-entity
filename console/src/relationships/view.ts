/**
 * CN-004: DOM builders for the relationships screen.
 *
 * renderRelationships lays the four review sections into a container, one
 * ui-card per relationship. The view calls no API and decides nothing: every
 * button hands its relationship id to the RelationshipHandlers the screen
 * passes in. All text reaches the DOM as textContent or attributes, never as
 * parsed HTML, so values from the API cannot inject markup.
 */

import type { GraphRelationship, SchemaGraph } from "../api/types.js";
import { evidenceText, summary } from "./model.js";
import type { RelationshipGroups } from "./model.js";

/** One callback per action a card offers, each given the relationship id. */
export interface RelationshipHandlers {
  onConfirm(id: string): void;
  onReject(id: string): void;
  onUndo(id: string): void;
  onRemove(id: string): void;
}

interface SectionSpec {
  /** The bucket of the groups this section renders. */
  key: keyof RelationshipGroups;
  /** Section heading, prefixed with the live count. */
  title: string;
  /** Message the empty section shows instead of nothing. */
  emptyMessage: string;
}

/** Screen order: what to review first, then the settled buckets. */
const SECTIONS: readonly SectionSpec[] = [
  { key: "proposed", title: "Proposed", emptyMessage: "Nothing is waiting for review." },
  { key: "confirmed", title: "Confirmed", emptyMessage: "No proposals have been confirmed yet." },
  { key: "declared", title: "Declared", emptyMessage: "No source declares foreign keys." },
  { key: "rejected", title: "Rejected", emptyMessage: "Nothing has been rejected." },
];

/** Replaces the container's children with the four review sections. */
export function renderRelationships(
  container: HTMLElement,
  groups: RelationshipGroups,
  graph: SchemaGraph,
  handlers: RelationshipHandlers,
): void {
  const sections = SECTIONS.map((section) => {
    const items = groups[section.key];
    const element = document.createElement("section");
    element.className = "relationships-section";
    element.setAttribute("data-section", section.key);

    const heading = document.createElement("h2");
    heading.className = "section-heading";
    heading.textContent = `${section.title} (${String(items.length)})`;
    element.appendChild(heading);

    if (items.length === 0) {
      element.appendChild(textRow(section.emptyMessage));
      return element;
    }
    for (const relationship of items) {
      element.appendChild(buildCard(relationship, graph, handlers));
    }
    return element;
  });
  container.replaceChildren(...sections);
}

/**
 * Disables or re-enables every ui-button inside the card for one
 * relationship id. No-op when that card is not under root — a reload may
 * already have replaced it.
 */
export function setRelationshipButtonsEnabled(
  root: HTMLElement,
  id: string,
  enabled: boolean,
): void {
  for (const card of root.querySelectorAll("ui-card")) {
    if (card.getAttribute("data-relationship-id") !== id) {
      continue;
    }
    for (const button of card.querySelectorAll("ui-button")) {
      if (enabled) {
        button.removeAttribute("disabled");
      } else {
        button.setAttribute("disabled", "");
      }
    }
  }
}

/** One relationship as a card: summary title, origin, confidence, evidence, actions. */
function buildCard(
  relationship: GraphRelationship,
  graph: SchemaGraph,
  handlers: RelationshipHandlers,
): HTMLElement {
  const card = document.createElement("ui-card");
  card.setAttribute("data-relationship-id", relationship.id);
  card.setAttribute("title", summary(relationship, graph));

  card.appendChild(textRow(`Origin: ${relationship.origin}`));
  if (relationship.confidence !== null) {
    const percent = String(Math.round(relationship.confidence * 100));
    card.appendChild(textRow(`Confidence: ${percent}%`));
  }
  const evidence = evidenceText(relationship);
  card.appendChild(textRow(`Evidence: ${evidence === "" ? "none" : evidence}`));

  const buttons = buttonsFor(relationship, handlers);
  if (buttons.length > 0) {
    const actions = document.createElement("div");
    actions.className = "relationship-actions";
    actions.replaceChildren(...buttons);
    card.appendChild(actions);
  }
  return card;
}

/**
 * The actions one relationship offers: proposals get Confirm and Reject,
 * decisions already stored get Undo (clears the decision), declared keys are
 * the databases' own and get nothing, and manual relationships additionally
 * get Remove, which deletes the asserted link.
 */
function buttonsFor(
  relationship: GraphRelationship,
  handlers: RelationshipHandlers,
): HTMLElement[] {
  const { id, status, origin } = relationship;
  const buttons: HTMLElement[] = [];
  if (status === "proposed") {
    buttons.push(actionButton("Confirm", "confirm", "primary", () => handlers.onConfirm(id)));
    buttons.push(actionButton("Reject", "reject", "secondary", () => handlers.onReject(id)));
  }
  if (status === "confirmed" || status === "rejected") {
    buttons.push(actionButton("Undo", "undo", "secondary", () => handlers.onUndo(id)));
  }
  if (origin === "manual") {
    buttons.push(actionButton("Remove", "remove", "ghost", () => handlers.onRemove(id)));
  }
  return buttons;
}

function actionButton(
  label: string,
  action: string,
  variant: string,
  onClick: () => void,
): HTMLElement {
  const button = document.createElement("ui-button");
  button.setAttribute("label", label);
  button.setAttribute("data-action", action);
  button.setAttribute("variant", variant);
  button.addEventListener("click", () => {
    // setRelationshipButtonsEnabled marks buttons whose decision is in
    // flight, but ui-button's disabled only blocks its inner button: a click
    // that reaches the host still fires this listener, so ignore it here.
    if (button.hasAttribute("disabled")) {
      return;
    }
    onClick();
  });
  return button;
}

function textRow(content: string): HTMLElement {
  const text = document.createElement("ui-text");
  text.setAttribute("content", content);
  return text;
}
