/**
 * CN-005: pure view model for the links screen.
 *
 * Shapes the combined schema document into what the four dropdowns show and
 * what the Create button may submit: entity and column options, the local
 * validation rule for a link, and the manual relationships to list. The
 * module touches no DOM and calls no API, so the screen stays thin and this
 * logic is testable under plain node --test.
 *
 * Option labels follow the relationships screen's house style: the overlay
 * label where the document carries one, otherwise the discovered name.
 * Column ids are what the API takes (source:entity.column), so an option's
 * value is always an id and its label is always for humans.
 */

import type { GraphRelationship, SchemaGraph } from "../api/types.js";

/** One ui-dropdown entry: the id sent to the API, and readable text for the list. */
export interface DropdownOption {
  value: string;
  label: string;
}

function compareIds(a: { id: string }, b: { id: string }): number {
  if (a.id < b.id) {
    return -1;
  }
  return a.id > b.id ? 1 : 0;
}

/**
 * Every entity as a dropdown option, sorted by id so options group by source
 * and stay stable across fetches. The label reads "source · entity" (overlay
 * label when set, discovered name otherwise) so the same table name in two
 * sources is still tellable apart.
 */
export function entityOptions(graph: SchemaGraph): DropdownOption[] {
  return [...graph.entities]
    .sort(compareIds)
    .map((entity) => ({
      value: entity.id,
      label: `${entity.source} · ${entity.label ?? entity.name}`,
    }));
}

/**
 * The columns of one entity (by its source:entity id) as dropdown options,
 * kept in discovery order — table order reads better than alphabetical for
 * a column list. An entity the document does not carry yields no options.
 */
export function columnOptions(graph: SchemaGraph, entityId: string): DropdownOption[] {
  const entity = graph.entities.find((candidate) => candidate.id === entityId);
  if (entity === undefined) {
    return [];
  }
  return entity.columns.map((column) => ({
    value: column.id,
    label: column.label ?? column.name,
  }));
}

/**
 * The screen's local rule for a link, checked before any request: both ends
 * must be chosen, and they must be two different columns. Returns the message
 * to show when the pair is not submittable, or null when it is. Anything the
 * server also enforces (unknown ids, say) stays the server's job.
 */
export function validateLink(from: string, to: string): string | null {
  if (from === "" || to === "") {
    return "Choose a column on both sides before creating the link.";
  }
  if (from === to) {
    return "Both ends are the same column — a link must join two different columns.";
  }
  return null;
}

/**
 * The relationships a person asserted by hand, sorted by id — the links this
 * screen lists and can remove. The input graph is never mutated.
 */
export function manualRelationships(graph: SchemaGraph): GraphRelationship[] {
  return graph.relationships
    .filter((relationship) => relationship.origin === "manual")
    .sort(compareIds);
}
