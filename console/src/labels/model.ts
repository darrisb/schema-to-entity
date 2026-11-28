/**
 * CN-006: pure view model for the labels screen.
 *
 * Shapes one entity of the combined document into the rows the screen edits:
 * the entity itself first, then one row per column in discovery order. Each
 * row carries the id setLabel/removeLabel act on and the Label/Description
 * values prefilled from the document (a null overlay field prefills empty, so
 * an unlabelled column starts blank rather than echoing its discovered name —
 * the row heading already shows that). The dirty rule that decides whether
 * Save may be clicked, and the payload a save sends, live here too, so the
 * screen only builds elements and wires events. No DOM, no fetch.
 */

import type { GraphColumn, GraphEntity, SchemaGraph } from "../api/types.js";

/** One editable row of the screen: its target id and its prefilled fields. */
export interface LabelRow {
  /** Entity or column id — exactly what the label endpoints take. */
  id: string;
  kind: "entity" | "column";
  /** Card heading naming the object, so the row is identifiable at a glance. */
  heading: string;
  /** Current overlay label; "" when the document carries none. */
  label: string;
  /** Current overlay description; "" when the document carries none. */
  description: string;
}

/** The two field values of one row, as they stand in the inputs. */
export interface LabelValues {
  label: string;
  description: string;
}

/**
 * The rows for one entity (by its source:entity id): the entity row, then a
 * row per column in discovery order. An entity the document does not carry
 * yields no rows. The graph is never mutated.
 */
export function labelRows(graph: SchemaGraph, entityId: string): LabelRow[] {
  const entity = graph.entities.find((candidate) => candidate.id === entityId);
  if (entity === undefined) {
    return [];
  }
  return [entityRow(entity), ...entity.columns.map(columnRow)];
}

function entityRow(entity: GraphEntity): LabelRow {
  return {
    id: entity.id,
    kind: "entity",
    heading: `Entity · ${entity.source} · ${entity.name}`,
    label: entity.label ?? "",
    description: entity.description ?? "",
  };
}

function columnRow(column: GraphColumn): LabelRow {
  return {
    id: column.id,
    kind: "column",
    heading: `Column · ${column.name} (${column.type})`,
    label: column.label ?? "",
    description: column.description ?? "",
  };
}

/**
 * Save is submittable only once a field differs from the document value it
 * was prefilled with; typing a value back to the original disables it again.
 */
export function isDirty(baseline: LabelValues, current: LabelValues): boolean {
  return current.label !== baseline.label || current.description !== baseline.description;
}

/**
 * What PUT /api/graph/overlay/labels receives for the typed values: both
 * fields trimmed, and a blank description sent as null (the API's "no
 * description"), never as an empty string. A blank label stays blank here —
 * the server's 400 for it is shown next to the row, which keeps this rule
 * single-sourced in the backend.
 */
export function labelChange(values: LabelValues): { label: string; description: string | null } {
  const description = values.description.trim();
  return {
    label: values.label.trim(),
    description: description === "" ? null : description,
  };
}
