/**
 * CN-008: pure view model for the drift screen.
 *
 * Shapes a DriftReport into what the screen shows: a readable line per change,
 * the changes bucketed by severity in review order (breaking first), an impact
 * line listing what each change affects, and the {from, to} id pairs a reviewer
 * can confirm as renames. The module touches no DOM and calls no API, so the
 * screen stays thin and this logic is testable under plain node --test.
 *
 * Wire shapes follow the Java records (see api/types.ts): DriftReport.Status
 * arrives kebab-cased ("baseline-created") via @JsonValue, while SchemaChange
 * Type and Severity arrive as their enum names ("ENTITY_ADDED", "BREAKING").
 * A change carries the entity spelled as its snapshot spells it and a column
 * for column-level changes; rename candidates name the paired object in
 * relatedTo. Ids are rebuilt as the graph and overlay spell them — lower-cased
 * source:entity and source:entity.column — because DriftController normalises
 * rename ids to exactly that shape.
 */

import type {
  ChangeImpact,
  DriftReport,
  SchemaChange,
  SchemaChangeSeverity,
} from "../api/types.js";

/**
 * The severities in the order the screen groups changes: what breaks a reader
 * first, then what a person must look at, then what only added something.
 */
export const SEVERITY_ORDER: readonly SchemaChangeSeverity[] = ["BREAKING", "REVIEW", "ADDITIVE"];

/** Where one severity sorts in SEVERITY_ORDER: BREAKING 0, REVIEW 1, ADDITIVE 2. */
export function severityOrder(severity: SchemaChangeSeverity): number {
  return SEVERITY_ORDER.indexOf(severity);
}

/** A readable label for one severity's section heading. */
export const SEVERITY_LABELS: Record<SchemaChangeSeverity, string> = {
  BREAKING: "Breaking",
  REVIEW: "Needs review",
  ADDITIVE: "Additive",
};

/** One drift change paired with the impact entry that describes its consequences. */
export interface ChangeWithImpact {
  change: SchemaChange;
  /** The impact by position, or null when the report carried none for it. */
  impact: ChangeImpact | null;
}

/** The old and new graph ids of one rename candidate, ready for confirmRename. */
export interface RenameCandidate {
  from: string;
  to: string;
}

/** One severity's changes, in review order. */
export interface SeverityGroup {
  severity: SchemaChangeSeverity;
  changes: ChangeWithImpact[];
}

/** A state value that may be null shown as text; null reads as a dash. */
function state(value: string | null): string {
  return value ?? "\u2014";
}

/** A trailing "(state)" that vanishes entirely when there is no state to show. */
function inParentheses(value: string | null): string {
  return value === null || value === "" ? "" : ` (${value})`;
}

/** The "source:entity" or "source:entity.column" id, lower-cased as the graph spells it. */
function graphId(source: string, entity: string, column: string | null): string {
  const id = `${source.toLowerCase()}:${entity.toLowerCase()}`;
  return column === null ? id : `${id}.${column.toLowerCase()}`;
}

/** The entity.column the change's column-level lines name. */
function columnTarget(change: SchemaChange): string {
  return `${change.entity}.${change.column ?? "\u2014"}`;
}

/** "x → y" for the two states a rename candidate reports. */
function renameStates(change: SchemaChange): string {
  return ` (${state(change.before)} \u2192 ${state(change.after)})`;
}

/**
 * One readable line for a change, spelled the way the api/README change table
 * reads. Additions name the new state, removals the state that is gone, and
 * changes between two states show both; the rename candidates name what the
 * removed object is matched with (relatedTo).
 */
export function changeText(change: SchemaChange): string {
  const entity = change.entity;
  const column = columnTarget(change);
  switch (change.type) {
    case "ENTITY_ADDED":
      return `Entity ${entity} added${inParentheses(change.after)}`;
    case "ENTITY_REMOVED":
      return `Entity ${entity} removed${inParentheses(change.before)}`;
    case "ENTITY_KIND_CHANGED":
      return `Entity ${entity} changed kind from ${state(change.before)} to ${state(change.after)}`;
    case "ENTITY_RENAME_CANDIDATE":
      return `Entity ${entity} may be renamed to ${state(change.relatedTo)}${renameStates(change)}`;
    case "COLUMN_ADDED":
      return `Column ${column} added${inParentheses(change.after)}`;
    case "COLUMN_REMOVED":
      return `Column ${column} removed${inParentheses(change.before)}`;
    case "COLUMN_TYPE_CHANGED":
      return `Column ${column} changed type from ${state(change.before)} to ${state(change.after)}`;
    case "COLUMN_NULLABILITY_CHANGED":
      return `Column ${column} changed nullability from ${state(change.before)} to ${state(change.after)}`;
    case "COLUMN_RENAME_CANDIDATE":
      return `Column ${column} may be renamed to ${state(change.relatedTo)}${renameStates(change)}`;
    case "KEY_COLUMNS_CHANGED":
      return `Key of ${entity} changed from ${state(change.before)} to ${state(change.after)}`;
    case "FOREIGN_KEY_ADDED":
      return `Foreign key on ${column} added${inParentheses(change.after)}`;
    case "FOREIGN_KEY_REMOVED":
      return `Foreign key on ${column} removed${inParentheses(change.before)}`;
  }
}

/** "Nothing" / "1 change" / "2 changes". */
function plural(value: number, word: string): string {
  if (value === 0) {
    return `no ${word}s`;
  }
  return value === 1 ? `1 ${word}` : `${String(value)} ${word}s`;
}

/** The status line a source's card shows, with the generic error for an unavailable one. */
export function statusText(report: DriftReport): string {
  switch (report.status) {
    case "unchanged":
      return "Unchanged since the baseline";
    case "changed":
      return `Changed \u2014 ${plural(report.changes.length, "change")} from the baseline`;
    case "baseline-created":
      return "First check \u2014 baseline created from this snapshot";
    case "unavailable":
      return `Unavailable \u2014 ${report.error ?? "schema discovery failed"}`;
  }
}

/** When this check ran, shown as the ISO instant the report carries. */
export function checkedText(report: DriftReport): string {
  return `Checked ${report.checkedAt}`;
}

function appendList(parts: string[], label: string, ids: readonly string[]): void {
  if (ids.length > 0) {
    parts.push(`${label}: ${ids.join(", ")}`);
  }
}

/**
 * What one change affects, as one line: every non-empty bucket of ids the
 * ChangeImpact names (labels, decisions, manual relationships, baseline
 * foreign keys) plus the saved pages and the components within them. A change
 * nothing refers to reads "Nothing refers to this change."
 */
export function impactText(impact: ChangeImpact | null): string {
  if (impact === null) {
    return "";
  }
  const parts: string[] = [];
  appendList(parts, "Labels", impact.labels);
  appendList(parts, "Decisions", impact.decisions);
  appendList(parts, "Manual relationships", impact.manualRelationships);
  appendList(parts, "Foreign keys", impact.foreignKeys);
  if (impact.pages.length > 0) {
    const pages = impact.pages.map((page) => `${page.pageId} (${page.componentIds.join(", ")})`);
    appendList(parts, "Pages", pages);
  }
  return parts.length === 0 ? "Nothing refers to this change." : `Affects \u2014 ${parts.join("; ")}`;
}

/**
 * The report's changes bucketed by severity, in SEVERITY_ORDER, each change
 * paired with the impact entry at the same index (impact lines up with changes
 * by position). Empty severities are dropped, and an unknown severity is
 * ignored rather than guessed at. The input report is not mutated.
 */
export function groupChanges(report: DriftReport): SeverityGroup[] {
  const buckets = new Map<SchemaChangeSeverity, ChangeWithImpact[]>();
  report.changes.forEach((change, index) => {
    const impact = index < report.impact.length ? (report.impact[index] ?? null) : null;
    const entry: ChangeWithImpact = { change, impact };
    const bucket = buckets.get(change.severity);
    if (bucket === undefined) {
      buckets.set(change.severity, [entry]);
    } else {
      bucket.push(entry);
    }
  });
  const groups: SeverityGroup[] = [];
  for (const severity of SEVERITY_ORDER) {
    const bucket = buckets.get(severity);
    if (bucket !== undefined) {
      groups.push({ severity, changes: bucket });
    }
  }
  return groups;
}

/**
 * The rename pair one change offers, or null when the change is not a rename
 * candidate or names no counterpart. A column candidate pairs the two columns
 * within the changed entity (source:entity.column on both sides); an entity
 * candidate pairs the two whole entities (source:entity).
 */
export function renameCandidate(report: DriftReport, change: SchemaChange): RenameCandidate | null {
  const relatedTo = change.relatedTo;
  if (relatedTo === null) {
    return null;
  }
  if (change.type === "COLUMN_RENAME_CANDIDATE" && change.column !== null) {
    return {
      from: graphId(report.source, change.entity, change.column),
      to: graphId(report.source, change.entity, relatedTo),
    };
  }
  if (change.type === "ENTITY_RENAME_CANDIDATE") {
    return {
      from: graphId(report.source, change.entity, null),
      to: graphId(report.source, relatedTo, null),
    };
  }
  return null;
}

/**
 * Every rename candidate in a report, in change order, as the {from, to} graph
 * ids confirmRename takes. Changes that are not candidates, or that name no
 * counterpart, are skipped.
 */
export function renameCandidates(report: DriftReport): RenameCandidate[] {
  const candidates: RenameCandidate[] = [];
  for (const change of report.changes) {
    const candidate = renameCandidate(report, change);
    if (candidate !== null) {
      candidates.push(candidate);
    }
  }
  return candidates;
}

/** Whether a changed report offers an Accept button (it must name the snapshot). */
export function canAccept(report: DriftReport): boolean {
  return report.status === "changed" && report.currentFingerprint !== null;
}

/** The confirm question an Accept asks before sending the fingerprint. */
export function acceptMessage(report: DriftReport): string {
  return `Accept the current schema of "${report.source}" as its new baseline?`;
}
