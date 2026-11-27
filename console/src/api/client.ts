/**
 * CN-002: typed client for the Schema to Entity endpoints the console uses.
 *
 * Every URL is relative (/api/…) so the dev-server proxy and any mounted
 * deployment work unchanged. The client touches no DOM and takes fetch as an
 * injectable, so tests run it under plain node --test with a fake. Non-2xx
 * responses throw ApiError carrying the JSON body's message/error field when
 * present; 204 responses resolve to undefined. Requests that send JSON set
 * Content-Type: application/json.
 */

import type {
  DataPage,
  DriftReport,
  EntityDescriptor,
  RelationshipStatus,
  SchemaGraph,
} from "./types.js";

/** The fetch shape the client needs: relative URL strings and a Response. */
export type FetchImpl = (input: string, init?: RequestInit) => Promise<Response>;

/** A non-2xx response; message comes from the body or falls back to the status text. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Paging and sorting for one data query (QueryParameterParser names). */
export interface QueryOptions {
  limit?: number;
  offset?: number;
  sort?: string;
}

/** The admin console's whole API surface, as returned by createClient. */
export interface ApiClient {
  /** The combined document; rejected relationships are kept when includeRejected. */
  getGraph(includeRejected: boolean): Promise<SchemaGraph>;
  /** Confirm or reject a relationship; null clears the decision. Returns 204. */
  decideRelationship(id: string, status: RelationshipStatus | null): Promise<void>;
  /** Assert a manual relationship between two column ids; returns its relationship id. */
  addManualRelationship(from: string, to: string): Promise<{ id: string }>;
  /** Remove a manual relationship and/or decision; returns 204. */
  removeRelationship(id: string): Promise<void>;
  /** Label an entity or column id, replacing any previous label; returns 204. */
  setLabel(id: string, label: string, description: string | null): Promise<void>;
  /** Remove the label of an id; returns 204. */
  removeLabel(id: string): Promise<void>;
  /** The entity descriptors of one data source. */
  listEntities(source: string): Promise<EntityDescriptor[]>;
  /** One page of rows of one entity. */
  queryEntity(source: string, entity: string, options?: QueryOptions): Promise<DataPage>;
  /** The last report of every configured source; check=true runs fresh checks. */
  getDrift(check: boolean): Promise<DriftReport[]>;
  /** Accept the snapshot the fingerprint names and get the fresh report back. */
  acceptDrift(source: string, fingerprint: string): Promise<DriftReport>;
  /** Move overlay edits from one id to another after a confirmed rename; returns 204. */
  confirmRename(source: string, from: string, to: string): Promise<void>;
}

/** Appends a query string when the params are non-empty, so plain GETs stay bare. */
function withQuery(path: string, params: URLSearchParams): string {
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

/**
 * The error message to carry: a JSON body's "message" or "error" field when
 * it has one (the API returns {"error": …} everywhere), else the status
 * text, else a generic HTTP label when the body and text are both absent.
 */
async function readErrorMessage(response: Response): Promise<string> {
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  if (body !== null && typeof body === "object") {
    const fields = body as Record<string, unknown>;
    for (const key of ["message", "error"]) {
      const value = fields[key];
      if (typeof value === "string" && value.length > 0) {
        return value;
      }
    }
  }
  return response.statusText || `HTTP ${response.status}`;
}

/** One request: JSON in when a body is given, parsed JSON or undefined out. */
async function request<T>(
  fetchImpl: FetchImpl,
  method: string,
  url: string,
  payload?: unknown,
): Promise<T> {
  const init: RequestInit = { method };
  if (payload !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(payload);
  }
  const response = await fetchImpl(url, init);
  if (!response.ok) {
    throw new ApiError(response.status, await readErrorMessage(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Creates the client. Pass a fetch implementation in tests; in the browser
 * the global fetch is used.
 */
export function createClient(fetchImpl: FetchImpl = fetch): ApiClient {
  return {
    getGraph(includeRejected) {
      const params = new URLSearchParams({ includeRejected: String(includeRejected) });
      return request<SchemaGraph>(fetchImpl, "GET", withQuery("/api/graph", params));
    },

    decideRelationship(id, status) {
      // status null clears the decision: the body must carry the null, not omit it.
      return request<void>(fetchImpl, "PUT", "/api/graph/overlay/relationships", {
        id,
        status,
      });
    },

    addManualRelationship(from, to) {
      return request<{ id: string }>(fetchImpl, "POST", "/api/graph/overlay/relationships", {
        from,
        to,
      });
    },

    removeRelationship(id) {
      const params = new URLSearchParams({ id });
      return request<void>(
        fetchImpl,
        "DELETE",
        withQuery("/api/graph/overlay/relationships", params),
      );
    },

    setLabel(id, label, description) {
      return request<void>(fetchImpl, "PUT", "/api/graph/overlay/labels", {
        id,
        label,
        description,
      });
    },

    removeLabel(id) {
      const params = new URLSearchParams({ id });
      return request<void>(fetchImpl, "DELETE", withQuery("/api/graph/overlay/labels", params));
    },

    listEntities(source) {
      return request<EntityDescriptor[]>(fetchImpl, "GET", `/api/data/${encodeURIComponent(source)}`);
    },

    queryEntity(source, entity, options = {}) {
      const params = new URLSearchParams();
      if (options.limit !== undefined) {
        params.set("limit", String(options.limit));
      }
      if (options.offset !== undefined) {
        params.set("offset", String(options.offset));
      }
      if (options.sort !== undefined) {
        params.set("sort", options.sort);
      }
      const path = `/api/data/${encodeURIComponent(source)}/${encodeURIComponent(entity)}`;
      return request<DataPage>(fetchImpl, "GET", withQuery(path, params));
    },

    getDrift(check) {
      const params = new URLSearchParams({ check: String(check) });
      return request<DriftReport[]>(fetchImpl, "GET", withQuery("/api/graph/drift", params));
    },

    acceptDrift(source, fingerprint) {
      const path = `/api/graph/drift/${encodeURIComponent(source)}/accept`;
      return request<DriftReport>(fetchImpl, "POST", path, { fingerprint });
    },

    confirmRename(source, from, to) {
      const path = `/api/graph/drift/${encodeURIComponent(source)}/renames`;
      return request<void>(fetchImpl, "POST", path, { from, to });
    },
  };
}
