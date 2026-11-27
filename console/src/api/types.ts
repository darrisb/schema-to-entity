/**
 * CN-002: TypeScript mirrors of the JSON the API's Jackson serializers write
 * for the records under com.example.dynamicmeta.{graph,data,drift}. Field
 * names and value shapes follow the Java records, not guesses: enum values
 * arrive as their Java names (ColumnCategory, SchemaChange.Type and
 * SchemaChange.Severity serialize as "NUMBER", "ENTITY_ADDED", "BREAKING",
 * …), DriftReport.Status carries @JsonValue so it arrives kebab-case
 * ("baseline-created"), and java.time.Instant arrives as an ISO-8601 string.
 * label and description are optional display fields and may be null.
 */

/** Coarse type category of a column (com.example.dynamicmeta.data.ColumnCategory). */
export type ColumnCategory = "NUMBER" | "BOOLEAN" | "DATE" | "TIMESTAMP" | "TEXT" | "OTHER";

/** Review state of a relationship (GraphRelationship.status). */
export type RelationshipStatus = "declared" | "proposed" | "confirmed" | "rejected";

/** Where a relationship came from (GraphRelationship.origin). */
export type RelationshipOrigin = "declared" | "inferred" | "manual";

/** Whether a relationship stays inside one source (GraphRelationship.scope). */
export type RelationshipScope = "within-source" | "cross-source";

/** Availability of a configured source (GraphSource.status). */
export type GraphSourceStatus = "ok" | "unavailable";

/** One configured source of the document (com.example.dynamicmeta.graph.GraphSource). */
export interface GraphSource {
  id: string;
  databaseType: string;
  catalog: string | null;
  schema: string | null;
  status: GraphSourceStatus;
  error: string | null;
}

/** One column of an entity (com.example.dynamicmeta.graph.GraphColumn). */
export interface GraphColumn {
  /** source:entity.column, lower-cased. */
  id: string;
  name: string;
  type: string;
  category: ColumnCategory;
  nullable: boolean;
  label: string | null;
  description: string | null;
}

/** One table or view of a source (com.example.dynamicmeta.graph.GraphEntity). */
export interface GraphEntity {
  /** source:entity, lower-cased. */
  id: string;
  source: string;
  name: string;
  /** "table", "view" or "materialized-view" as discovered. */
  kind: string;
  label: string | null;
  description: string | null;
  keyColumns: string[];
  columns: GraphColumn[];
  baseTables: string[];
}

/** One relationship between two columns (com.example.dynamicmeta.graph.GraphRelationship). */
export interface GraphRelationship {
  /** fromColumnId->toColumnId, lower-cased. */
  id: string;
  from: string;
  to: string;
  origin: RelationshipOrigin;
  scope: RelationshipScope;
  status: RelationshipStatus;
  confidence: number | null;
  evidence: string[];
}

/** The combined schema document (com.example.dynamicmeta.graph.SchemaGraph). */
export interface SchemaGraph {
  specVersion: string;
  /** ISO-8601 instant the snapshot was taken. */
  generatedAt: string;
  sources: GraphSource[];
  entities: GraphEntity[];
  relationships: GraphRelationship[];
  /** Overlay ids that no longer match anything. */
  orphans: string[];
}

/** One column of an entity descriptor from the data API. */
export interface ColumnDescriptor {
  name: string;
  type: string;
  category: ColumnCategory;
  nullable: boolean;
}

/** One entity of GET /api/data/{source} (DataQueryService.EntityDescriptor). */
export interface EntityDescriptor {
  name: string;
  kind: string;
  keyColumns: string[];
  columns: ColumnDescriptor[];
  /** Same value as the X-Schema-Version header (DR-008). */
  schemaVersion: string;
}

/** One page of rows plus paging metadata (DataQueryService.DataPage). */
export interface DataPage {
  /** Row objects keyed by column name; values keep their JSON types. */
  items: Record<string, unknown>[];
  /** Null when the caller sent total=false. */
  total: number | null;
  limit: number;
  /** Null when the caller sent no offset. */
  offset: number | null;
}

/** What a drift check found (DriftReport.Status, serialized @JsonValue). */
export type DriftStatus = "unchanged" | "changed" | "baseline-created" | "unavailable";

/** The kind of difference a SchemaChange describes (SchemaChange.Type). */
export type SchemaChangeType =
  | "ENTITY_ADDED"
  | "ENTITY_REMOVED"
  | "ENTITY_KIND_CHANGED"
  | "ENTITY_RENAME_CANDIDATE"
  | "COLUMN_ADDED"
  | "COLUMN_REMOVED"
  | "COLUMN_TYPE_CHANGED"
  | "COLUMN_NULLABILITY_CHANGED"
  | "COLUMN_RENAME_CANDIDATE"
  | "KEY_COLUMNS_CHANGED"
  | "FOREIGN_KEY_ADDED"
  | "FOREIGN_KEY_REMOVED";

/** How much a change matters (SchemaChange.Severity). */
export type SchemaChangeSeverity = "ADDITIVE" | "BREAKING" | "REVIEW";

/** One classified difference from the baseline (com.example.dynamicmeta.drift.SchemaChange). */
export interface SchemaChange {
  type: SchemaChangeType;
  severity: SchemaChangeSeverity;
  entity: string;
  /** Null for entity-level changes. */
  column: string | null;
  /** Human-readable baseline state, null when there was none. */
  before: string | null;
  /** Human-readable current state, null when there is none. */
  after: string | null;
  /** What a rename candidate pairs with; null otherwise. */
  relatedTo: string | null;
}

/** A saved page and the components in it that use a changed object (PageImpact). */
export interface PageImpact {
  pageId: string;
  componentIds: string[];
}

/** What one change affects (com.example.dynamicmeta.drift.ChangeImpact). */
export interface ChangeImpact {
  change: SchemaChange;
  labels: string[];
  decisions: string[];
  manualRelationships: string[];
  foreignKeys: string[];
  pages: PageImpact[];
}

/** The outcome of one drift check for one source (com.example.dynamicmeta.drift.DriftReport). */
export interface DriftReport {
  source: string;
  status: DriftStatus;
  baselineFingerprint: string | null;
  /** Null when the source was unavailable. */
  currentFingerprint: string | null;
  baselineTakenAt: string | null;
  /** ISO-8601 instant this check ran. */
  checkedAt: string;
  changes: SchemaChange[];
  /** One entry per change, in the same order. */
  impact: ChangeImpact[];
  /** Generic reason the source was unavailable; null otherwise. */
  error: string | null;
}
