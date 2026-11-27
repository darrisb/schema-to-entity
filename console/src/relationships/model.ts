/**
 * CN-003: pure view model for the relationship screen.
 *
 * These functions turn the combined schema document into what the screen
 * shows: review buckets, readable endpoint labels and one-line summaries.
 * The module touches no DOM and calls no API, so the screen code stays thin
 * and this logic is testable under plain node --test.
 *
 * Rejected relationships only appear when the graph was fetched with
 * includeRejected=true (GET /api/graph drops them otherwise), so
 * groupRelationships simply buckets by status: the rejected bucket is empty
 * for a graph fetched without the flag and holds the stored rejections,
 * sorted by id, when the graph was fetched with it.
 */

import type { GraphColumn, GraphEntity, GraphRelationship, SchemaGraph } from "../api/types.js";

/** The four review buckets of the relationship screen. */
export interface RelationshipGroups {
  /** Sorted by confidence high to low, then by id; a null confidence sorts last. */
  proposed: GraphRelationship[];
  /** Sorted by id. */
  confirmed: GraphRelationship[];
  /** Sorted by id. */
  declared: GraphRelationship[];
  /** Sorted by id; empty unless the graph carried rejected relationships. */
  rejected: GraphRelationship[];
}

/** One readable end of a relationship: what source, entity and column it is. */
export interface RelationshipEnd {
  source: string;
  entityLabel: string;
  columnLabel: string;
}

/** Column ids look like source:entity.column, lower-cased, none containing ':' or '.'. */
const COLUMN_ID = /^([^:.]+):([^.:]+)\.(.+)$/;

/** Entity ids look like source:entity. */
function entityId(source: string, name: string): string {
  return `${source}:${name}`;
}

function compareIds(a: GraphRelationship, b: GraphRelationship): number {
  if (a.id < b.id) {
    return -1;
  }
  return a.id > b.id ? 1 : 0;
}

/** Confidence ordering helper: unknown (null) confidence sorts below every score. */
function confidenceOf(relationship: GraphRelationship): number {
  return relationship.confidence ?? -1;
}

/**
 * Buckets the graph's relationships by review status. proposed comes back
 * sorted by confidence high to low (ties, and the whole of every other
 * bucket, by id). The input graph is never mutated.
 */
export function groupRelationships(graph: SchemaGraph): RelationshipGroups {
  const groups: RelationshipGroups = { proposed: [], confirmed: [], declared: [], rejected: [] };
  for (const relationship of graph.relationships) {
    switch (relationship.status) {
      case "proposed":
        groups.proposed.push(relationship);
        break;
      case "confirmed":
        groups.confirmed.push(relationship);
        break;
      case "declared":
        groups.declared.push(relationship);
        break;
      case "rejected":
        groups.rejected.push(relationship);
        break;
    }
  }

  groups.proposed.sort(
    (a, b) => confidenceOf(b) - confidenceOf(a) || compareIds(a, b),
  );
  groups.confirmed.sort(compareIds);
  groups.declared.sort(compareIds);
  groups.rejected.sort(compareIds);
  return groups;
}

/**
 * Describes one column id as the screen shows it: the source it lives in and
 * overlay labels where set, falling back to the discovered names. An id the
 * document does not carry (a column drift removed, say) falls back to the
 * raw id's own parts so the screen still names it; an id that is not even in
 * source:entity.column form falls back to the whole raw id.
 */
export function describeEnd(graph: SchemaGraph, columnId: string): RelationshipEnd {
  for (const entity of graph.entities) {
    const column = findColumn(entity, columnId);
    if (column) {
      return {
        source: entity.source,
        entityLabel: entity.label ?? entity.name,
        columnLabel: column.label ?? column.name,
      };
    }
  }

  const parts = COLUMN_ID.exec(columnId);
  if (parts) {
    const [, source, entityName, columnName] = parts;
    const entity = graph.entities.find((candidate) => candidate.id === entityId(source, entityName));
    return {
      source: entity ? entity.source : source,
      entityLabel: entity ? entity.label ?? entity.name : entityName,
      columnLabel: columnName,
    };
  }
  return { source: columnId, entityLabel: columnId, columnLabel: columnId };
}

/**
 * One readable line for a relationship, such as
 * "billing · invoices.user_id → demo · users.id". Overlay labels replace the
 * name parts when the document carries them.
 */
export function summary(relationship: GraphRelationship, graph: SchemaGraph): string {
  return `${endText(describeEnd(graph, relationship.from))} → ${endText(describeEnd(graph, relationship.to))}`;
}

function endText(end: RelationshipEnd): string {
  return `${end.source} · ${end.entityLabel}.${end.columnLabel}`;
}

/**
 * How the analysis found each evidence token, in short words. Tokens the
 * console does not know pass through unchanged, so a new backend signal is
 * still visible rather than dropped.
 */
const EVIDENCE_PHRASES: Record<string, string> = {
  "view-column-name": "view column named like the base key",
  "name-match": "column name match",
  "type-match": "column type match",
  "key-name-match": "key name match",
};

/** The relationship's evidence as one readable phrase list; "" when it has none. */
export function evidenceText(relationship: GraphRelationship): string {
  return relationship.evidence.map((code) => EVIDENCE_PHRASES[code] ?? code).join(", ");
}

/**
 * Whether a relationship joins two sources: the graph's scope says so, or the
 * two column ids carry differing source prefixes. The prefix check makes a
 * scope field that disagrees with the ids read as cross-source, which is the
 * cautious direction — cross-source links are only proposals until confirmed.
 */
export function isCrossSource(relationship: GraphRelationship): boolean {
  if (relationship.scope === "cross-source") {
    return true;
  }
  return sourcePrefix(relationship.from) !== sourcePrefix(relationship.to);
}

/** The source part of a column id; "" when the id has no source prefix. */
function sourcePrefix(id: string): string {
  const colon = id.indexOf(":");
  return colon === -1 ? "" : id.slice(0, colon);
}

function findColumn(entity: GraphEntity, columnId: string): GraphColumn | undefined {
  return entity.columns.find((column) => column.id === columnId);
}
