/**
 * RestTransport — talks to the hosted Neo4j Agent Memory Service REST API.
 *
 * Endpoint should be the v1 root, e.g. `https://memory.neo4jlabs.com/v1`.
 * Routes the bridge-style `request(method, params)` calls to the appropriate
 * REST endpoints with snake_case ↔ camelCase translation on the wire.
 *
 * Hosted-native methods (added in Volume 5 of the spec) are routed natively.
 * Legacy bridge-only methods (add_preference, add_fact, etc.) throw
 * NotSupportedError because the hosted service has no equivalent.
 */

import {
  AuthenticationError,
  ConnectionError,
  NotSupportedError,
  TransportError,
} from "../errors.js";
import { camelToSnake, snakeToCamel } from "./casing.js";
import type { Transport } from "./index.js";

export type TokenProvider = () => string | Promise<string>;

export interface RestTransportOptions {
  /** Base URL — should end in /v1, e.g. https://memory.neo4jlabs.com/v1 */
  endpoint: string;

  /** Static `nams_*` API key. */
  apiKey?: string;

  /** Token provider (overrides apiKey if both supplied) — for OAuth refresh flows. */
  tokenProvider?: TokenProvider;

  /** Request timeout in milliseconds. Default: 30000. */
  timeout?: number;

  /** Additional headers to include in every request. */
  headers?: Record<string, string>;
}

type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

interface RestCall {
  method: HttpMethod;
  /** Path template; tokens like `{conversationId}` are replaced from params (camel-cased). */
  path: string;
  /** Param names that go in the URL path (camelCase) — stripped from the body. */
  pathParams?: string[];
  /** Param names that become query string parameters (camelCase). */
  queryParams?: string[];
  /** GET/DELETE → no body. */
  hasBody?: boolean;
  /** Optional response shaper for endpoints whose payload doesn't match bridge wire. */
  shape?: (raw: unknown, camelParams: Record<string, unknown>) => unknown;
}

/**
 * Bridge-method-name → REST-call mapping.
 *
 * Keys are snake_case bridge method names. Values describe how to dispatch.
 */
const ROUTES: Record<string, RestCall | "noop" | "unsupported"> = {
  // Lifecycle ----------------------------------------------------------------
  setup: "noop",
  teardown: "noop",
  // Hosted has no global clear; we delete every conversation owned by the API
  // key. This is best-effort — see clearAllData() for the implementation.
  clear_all_data: "noop",

  // Short-Term — legacy bridge methods (mapped where a clean REST equivalent
  // exists; bridge sessionId is treated as the conversationId UUID).
  add_message: {
    method: "POST",
    path: "/conversations/{sessionId}/messages",
    pathParams: ["sessionId"],
    hasBody: true,
  },
  get_conversation: {
    method: "GET",
    path: "/conversations/{sessionId}/messages",
    pathParams: ["sessionId"],
    queryParams: ["limit"],
    shape: (raw, p) => {
      const messages = (raw as { messages?: unknown[] })?.messages ?? raw ?? [];
      return {
        id: p["sessionId"],
        session_id: p["sessionId"],
        messages,
        created_at: null,
      };
    },
  },
  list_sessions: {
    method: "GET",
    path: "/conversations",
    queryParams: ["limit"],
    shape: (raw) => {
      const conversations = (raw as { conversations?: unknown[] })?.conversations ?? [];
      return conversations.map((c) => {
        const conv = c as Record<string, unknown>;
        return {
          session_id: conv["id"],
          message_count: conv["messageCount"] ?? 0,
          created_at: conv["createdAt"],
          updated_at: conv["updatedAt"],
        };
      });
    },
  },
  search_messages: {
    method: "POST",
    path: "/conversations/{sessionId}/search",
    pathParams: ["sessionId"],
    hasBody: true,
    shape: (raw) => (raw as { messages?: unknown[] })?.messages ?? [],
  },
  clear_session: {
    method: "DELETE",
    path: "/conversations/{sessionId}",
    pathParams: ["sessionId"],
  },
  delete_message: "unsupported",

  // Long-Term — legacy mapped methods
  add_entity: {
    method: "POST",
    path: "/entities",
    hasBody: true,
  },
  search_entities: {
    method: "POST",
    path: "/entities/search",
    hasBody: true,
    shape: (raw) => (raw as { entities?: unknown[] })?.entities ?? [],
  },
  add_preference: "unsupported",
  add_fact: "unsupported",
  search_preferences: "unsupported",
  get_entity_by_name: "unsupported",
  get_related_entities: "unsupported",
  add_relationship: "unsupported",
  merge_duplicate_entities: "unsupported",

  // Reasoning — legacy not directly representable in REST
  start_trace: "unsupported",
  add_step: "unsupported",
  record_tool_call: {
    method: "POST",
    path: "/reasoning/tool-calls",
    hasBody: true,
  },
  complete_trace: "unsupported",
  get_trace_with_steps: "unsupported",
  list_traces: "unsupported",
  get_tool_stats: "unsupported",
  get_similar_traces: "unsupported",

  // ---- Hosted-native methods (Volume 5 / Platinum tier) --------------------
  create_conversation: {
    method: "POST",
    path: "/conversations",
    hasBody: true,
  },
  list_conversations: {
    method: "GET",
    path: "/conversations",
    queryParams: ["limit"],
    shape: (raw) => (raw as { conversations?: unknown[] })?.conversations ?? raw,
  },
  get_conversation_metadata: {
    method: "GET",
    path: "/conversations/{conversationId}",
    pathParams: ["conversationId"],
  },
  delete_conversation: {
    method: "DELETE",
    path: "/conversations/{conversationId}",
    pathParams: ["conversationId"],
  },
  get_context: {
    method: "GET",
    path: "/conversations/{conversationId}/context",
    pathParams: ["conversationId"],
  },
  bulk_add_messages: {
    method: "POST",
    path: "/conversations/{conversationId}/messages/bulk",
    pathParams: ["conversationId"],
    hasBody: true,
    shape: (raw) => (raw as { messages?: unknown[] })?.messages ?? raw,
  },
  get_observations: {
    method: "GET",
    path: "/conversations/{conversationId}/observations",
    pathParams: ["conversationId"],
    queryParams: ["limit"],
    shape: (raw) => (raw as { observations?: unknown[] })?.observations ?? raw,
  },
  get_reflections: {
    method: "GET",
    path: "/conversations/{conversationId}/reflections",
    pathParams: ["conversationId"],
    shape: (raw) => (raw as { reflections?: unknown[] })?.reflections ?? raw,
  },
  list_entities: {
    method: "GET",
    path: "/entities",
    queryParams: ["type", "limit"],
    shape: (raw) => (raw as { entities?: unknown[] })?.entities ?? raw,
  },
  get_entity: {
    method: "GET",
    path: "/entities/{entityId}",
    pathParams: ["entityId"],
  },
  update_entity: {
    method: "PUT",
    path: "/entities/{entityId}",
    pathParams: ["entityId"],
    hasBody: true,
  },
  delete_entity: {
    method: "DELETE",
    path: "/entities/{entityId}",
    pathParams: ["entityId"],
  },
  set_entity_feedback: {
    method: "PUT",
    path: "/entities/{entityId}/feedback",
    pathParams: ["entityId"],
    hasBody: true,
  },
  get_entity_history: {
    method: "GET",
    path: "/entities/{entityId}/history",
    pathParams: ["entityId"],
  },
  merge_entities: {
    method: "POST",
    path: "/entities/{sourceId}/merge",
    pathParams: ["sourceId"],
    hasBody: true,
  },
  get_entity_graph: {
    method: "GET",
    path: "/entities/graph",
  },
  explain_step: {
    method: "GET",
    path: "/reasoning/explain/{stepId}",
    pathParams: ["stepId"],
  },
  get_trace_by_conversation: {
    method: "GET",
    path: "/reasoning/trace/{conversationId}",
    pathParams: ["conversationId"],
  },
  get_entity_provenance: {
    method: "GET",
    path: "/reasoning/provenance/{entityId}",
    pathParams: ["entityId"],
  },
  record_step: {
    method: "POST",
    path: "/reasoning/steps",
    hasBody: true,
  },
  list_steps: {
    method: "GET",
    path: "/reasoning/steps",
    queryParams: ["conversationId"],
  },
  cypher_query: {
    method: "POST",
    path: "/query",
    hasBody: true,
  },

  // Auth
  list_api_keys: {
    method: "GET",
    path: "/auth/api-keys",
    queryParams: ["workspaceId"],
  },
  create_api_key: {
    method: "POST",
    path: "/auth/api-keys",
    hasBody: true,
  },
  revoke_api_key: {
    method: "DELETE",
    path: "/auth/api-keys/{keyId}",
    pathParams: ["keyId"],
  },
  reveal_api_key: {
    method: "GET",
    path: "/auth/api-keys/{keyId}/reveal",
    pathParams: ["keyId"],
    queryParams: ["workspaceId"],
  },
  refresh_access_token: {
    method: "POST",
    path: "/auth/refresh",
    hasBody: true,
  },
};

export class RestTransport implements Transport {
  private readonly endpoint: string;
  private readonly apiKey?: string;
  private readonly tokenProvider?: TokenProvider;
  private readonly timeout: number;
  private readonly headers: Record<string, string>;

  constructor(options: RestTransportOptions) {
    this.endpoint = options.endpoint.replace(/\/+$/, "");
    this.apiKey = options.apiKey;
    this.tokenProvider = options.tokenProvider;
    this.timeout = options.timeout ?? 30_000;
    this.headers = options.headers ?? {};
  }

  async connect(): Promise<void> {
    // GET /conversations is a cheap auth check.
    try {
      const response = await fetch(`${this.endpoint}/conversations?limit=1`, {
        method: "GET",
        headers: await this.buildHeaders(),
        signal: AbortSignal.timeout(this.timeout),
      });
      if (response.status === 401 || response.status === 403) {
        throw new AuthenticationError(
          `Authentication failed against ${this.endpoint}: ${response.status} ${response.statusText}`,
        );
      }
      if (!response.ok && response.status >= 500) {
        throw new ConnectionError(
          `Server error from ${this.endpoint}: ${response.status} ${response.statusText}`,
        );
      }
    } catch (error) {
      if (error instanceof AuthenticationError || error instanceof ConnectionError) throw error;
      if (error instanceof TypeError) {
        throw new ConnectionError(
          `Failed to connect to ${this.endpoint}: ${(error as Error).message}`,
          { cause: error },
        );
      }
      if (error instanceof DOMException && error.name === "TimeoutError") {
        throw new ConnectionError(
          `Connection to ${this.endpoint} timed out after ${this.timeout}ms`,
          { cause: error },
        );
      }
      throw error;
    }
  }

  async close(): Promise<void> {}

  async request<T>(method: string, params: Record<string, unknown>): Promise<T> {
    const route = ROUTES[method];
    if (!route) {
      throw new NotSupportedError(
        `Method '${method}' is not implemented by RestTransport. ` +
          `Use BridgeTransport for full TCK conformance, or call a hosted-native method.`,
      );
    }
    if (route === "noop") return undefined as T;
    if (route === "unsupported") {
      throw new NotSupportedError(
        `Method '${method}' has no equivalent in the hosted Neo4j Agent Memory REST API. ` +
          `It is supported by BridgeTransport only.`,
      );
    }

    const camelParams = snakeToCamel<Record<string, unknown>>(params);

    // Substitute path params
    let path = route.path;
    const consumed = new Set<string>();
    for (const name of route.pathParams ?? []) {
      const v = camelParams[name];
      if (v === undefined || v === null || v === "") {
        throw new TransportError(
          `Missing required path parameter '${name}' for method '${method}'`,
          400,
          camelParams,
        );
      }
      path = path.replace(`{${name}}`, encodeURIComponent(String(v)));
      consumed.add(name);
    }

    // Build query string
    const queryEntries: [string, string][] = [];
    for (const name of route.queryParams ?? []) {
      const v = camelParams[name];
      if (v !== undefined && v !== null) {
        queryEntries.push([name, String(v)]);
        consumed.add(name);
      }
    }
    const query = queryEntries.length
      ? "?" + queryEntries.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&")
      : "";

    // Build body (anything not consumed)
    let body: string | undefined;
    if (route.hasBody) {
      const bodyObj: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(camelParams)) {
        if (!consumed.has(k) && v !== undefined && v !== null) {
          bodyObj[k] = v;
        }
      }
      body = JSON.stringify(bodyObj);
    }

    const url = `${this.endpoint}${path}${query}`;
    let response: Response;
    try {
      response = await fetch(url, {
        method: route.method,
        headers: await this.buildHeaders(route.hasBody),
        body,
        signal: AbortSignal.timeout(this.timeout),
      });
    } catch (error) {
      if (error instanceof TypeError) {
        throw new ConnectionError(
          `Request to ${url} failed: ${(error as Error).message}`,
          { cause: error },
        );
      }
      throw error;
    }

    if (response.status === 401 || response.status === 403) {
      throw new AuthenticationError(
        `Authentication failed: ${response.status} ${response.statusText}`,
      );
    }

    if (response.status === 204) return undefined as T;

    const text = await response.text();

    if (!response.ok) {
      let errorBody: unknown;
      try {
        errorBody = JSON.parse(text);
      } catch {
        errorBody = text;
      }
      const errMsg =
        typeof errorBody === "object" && errorBody !== null && "error" in errorBody
          ? String((errorBody as Record<string, unknown>)["error"])
          : `HTTP ${response.status}`;
      throw new TransportError(`${method} failed: ${errMsg}`, response.status, errorBody);
    }

    if (!text) return undefined as T;
    let parsed: unknown = JSON.parse(text);
    if (route.shape) parsed = route.shape(parsed, camelParams);
    return camelToSnake<T>(parsed);
  }

  private async buildHeaders(includeContentType = false): Promise<Record<string, string>> {
    const headers: Record<string, string> = { ...this.headers };
    if (includeContentType) headers["Content-Type"] = "application/json";
    const token = this.tokenProvider ? await this.tokenProvider() : this.apiKey;
    if (token) headers["Authorization"] = `Bearer ${token}`;
    return headers;
  }
}
