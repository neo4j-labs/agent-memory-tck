/**
 * HTTP Bridge Conformance Server for the TypeScript client.
 *
 * Implements the TCK HTTP bridge protocol so the Python TCK suite can validate
 * the TypeScript client end-to-end.
 *
 * Routes are POST /{snake_case_method}; bodies are snake_case JSON. The server
 * forwards each call to the upstream `MemoryClient` (configured via
 * `MEMORY_ENDPOINT` — set this to your bridge endpoint or to
 * `https://memory.neo4jlabs.com/v1` for hosted-mode conformance runs).
 *
 * Usage:
 *   MEMORY_ENDPOINT=http://localhost:7687 tsx conformance/server.ts
 *   MEMORY_ENDPOINT=https://memory.neo4jlabs.com/v1 \
 *     MEMORY_API_KEY=nams_... tsx conformance/server.ts
 *   # Then from the TCK repo:
 *   pytest -m bronze --bridge-url http://localhost:3001
 */

import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { MemoryClient } from "../src/client.js";

const PORT = parseInt(process.env["TCK_BRIDGE_PORT"] ?? "3001", 10);
const UPSTREAM = process.env["MEMORY_ENDPOINT"] ?? process.env["NEO4J_URI"] ?? "";
const API_KEY = process.env["MEMORY_API_KEY"];

if (!UPSTREAM) {
  console.error(
    "Set MEMORY_ENDPOINT (hosted service URL or bridge endpoint) or NEO4J_URI env var.",
  );
  process.exit(1);
}

const client = new MemoryClient({ endpoint: UPSTREAM, apiKey: API_KEY });

async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  const text = Buffer.concat(chunks).toString("utf-8");
  if (!text) return {};
  return JSON.parse(text) as Record<string, unknown>;
}

function jsonResponse(res: ServerResponse, data: unknown, status = 200): void {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(data));
}

function noContent(res: ServerResponse): void {
  res.writeHead(204);
  res.end();
}

type Handler = (body: Record<string, unknown>) => Promise<unknown>;

const handlers: Record<string, Handler> = {
  // ---- Lifecycle --------------------------------------------------------
  setup: async () => ({ ok: true, protocol_version: "0.2.0" }),
  teardown: async () => undefined,
  clear_all_data: async () => undefined,

  // ---- Short-Term Memory (Bronze) --------------------------------------
  add_message: async (body) =>
    client.shortTerm.addMessage(
      body["session_id"] as string,
      body["role"] as "user" | "assistant" | "system",
      body["content"] as string,
      { metadata: body["metadata"] as Record<string, unknown> | undefined },
    ),

  get_conversation: async (body) =>
    client.shortTerm.getConversation(body["session_id"] as string, {
      limit: body["limit"] as number | undefined,
    }),

  search_messages: async (body) =>
    client.shortTerm.searchMessages(body["query"] as string, {
      sessionId: body["session_id"] as string | undefined,
      limit: (body["limit"] as number) ?? 10,
      threshold: (body["threshold"] as number) ?? 0.7,
    }),

  list_sessions: async (body) =>
    client.shortTerm.listSessions({ limit: (body["limit"] as number) ?? 100 }),

  delete_message: async (body) => ({
    deleted: await client.shortTerm.deleteMessage(body["message_id"] as string),
  }),

  clear_session: async (body) => {
    await client.shortTerm.clearSession(body["session_id"] as string);
    return undefined;
  },

  // ---- Long-Term Memory (Silver) --------------------------------------
  add_entity: async (body) =>
    client.longTerm.addEntity(body["name"] as string, body["entity_type"] as string, {
      description: body["description"] as string | undefined,
    }),

  add_preference: async (body) =>
    client.longTerm.addPreference(body["category"] as string, body["preference"] as string, {
      context: body["context"] as string | undefined,
    }),

  add_fact: async (body) =>
    client.longTerm.addFact(
      body["subject"] as string,
      body["predicate"] as string,
      body["obj"] as string,
    ),

  search_entities: async (body) =>
    client.longTerm.searchEntities(body["query"] as string, {
      limit: (body["limit"] as number) ?? 10,
    }),

  search_preferences: async (body) =>
    client.longTerm.searchPreferences(body["query"] as string, {
      category: body["category"] as string | undefined,
      limit: (body["limit"] as number) ?? 10,
    }),

  get_entity_by_name: async (body) =>
    client.longTerm.getEntityByName(body["name"] as string),

  get_related_entities: async (body) =>
    client.longTerm.getRelatedEntities(body["entity_id"] as string, {
      relationshipType: body["relationship_type"] as string | undefined,
      depth: (body["depth"] as number) ?? 1,
    }),

  // ---- Reasoning Memory (Silver) --------------------------------------
  start_trace: async (body) =>
    client.reasoning.startTrace(body["session_id"] as string, body["task"] as string),

  add_step: async (body) =>
    client.reasoning.addStep(body["trace_id"] as string, {
      thought: body["thought"] as string | undefined,
      action: body["action"] as string | undefined,
      observation: body["observation"] as string | undefined,
    }),

  record_tool_call: async (body) =>
    client.reasoning.recordToolCall(
      body["step_id"] as string,
      body["tool_name"] as string,
      (body["arguments"] as Record<string, unknown>) ?? {},
      {
        result: body["result"],
        status: body["status"] as "success" | "failure" | undefined,
        durationMs: body["duration_ms"] as number | undefined,
        error: body["error"] as string | undefined,
      },
    ),

  complete_trace: async (body) =>
    client.reasoning.completeTrace(body["trace_id"] as string, {
      outcome: body["outcome"] as string | undefined,
      success: body["success"] as boolean | undefined,
    }),

  get_trace_with_steps: async (body) =>
    client.reasoning.getTraceWithSteps(body["trace_id"] as string),

  list_traces: async (body) =>
    client.reasoning.listTraces({
      sessionId: body["session_id"] as string | undefined,
      limit: (body["limit"] as number) ?? 100,
    }),

  get_tool_stats: async (body) =>
    client.reasoning.getToolStats(body["tool_name"] as string | undefined),

  // ---- Gold Tier ---------------------------------------------------------
  add_relationship: async (body) =>
    client.longTerm.addRelationship(
      body["source_id"] as string,
      body["target_id"] as string,
      body["relationship_type"] as string,
      { properties: body["properties"] as Record<string, unknown> | undefined },
    ),

  merge_duplicate_entities: async (body) =>
    client.longTerm.mergeDuplicateEntities(
      body["source_id"] as string,
      body["target_id"] as string,
      { canonicalName: body["canonical_name"] as string | undefined },
    ),

  get_similar_traces: async (body) =>
    client.reasoning.getSimilarTraces(body["task"] as string, {
      limit: (body["limit"] as number) ?? 5,
      successOnly: (body["success_only"] as boolean) ?? true,
    }),

  // ---- Volume 5 / Platinum Tier (hosted-native) -------------------------
  create_conversation: async (body) =>
    client.shortTerm.createConversation({
      userId: body["user_id"] as string,
      metadata: body["metadata"] as Record<string, unknown> | undefined,
    }),
  list_conversations: async (body) =>
    client.shortTerm.listConversations({ limit: body["limit"] as number | undefined }),
  get_conversation_metadata: async (body) =>
    client.shortTerm.getConversationMetadata(body["conversation_id"] as string),
  delete_conversation: async (body) => {
    await client.shortTerm.deleteConversation(body["conversation_id"] as string);
    return undefined;
  },
  get_context: async (body) => client.shortTerm.getContext(body["conversation_id"] as string),
  bulk_add_messages: async (body) =>
    client.shortTerm.bulkAddMessages(
      body["conversation_id"] as string,
      (body["messages"] as Array<{ role: "user" | "assistant" | "system"; content: string }>) ?? [],
    ),
  get_observations: async (body) =>
    client.shortTerm.getObservations(body["conversation_id"] as string, {
      limit: body["limit"] as number | undefined,
    }),
  get_reflections: async (body) =>
    client.shortTerm.getReflections(body["conversation_id"] as string),

  list_entities: async (body) =>
    client.longTerm.listEntities({
      type: body["type"] as string | undefined,
      limit: body["limit"] as number | undefined,
    }),
  get_entity: async (body) => client.longTerm.getEntity(body["entity_id"] as string),
  update_entity: async (body) =>
    client.longTerm.updateEntity(body["entity_id"] as string, {
      name: body["name"] as string | undefined,
      description: body["description"] as string | undefined,
    }),
  delete_entity: async (body) => {
    await client.longTerm.deleteEntity(body["entity_id"] as string);
    return undefined;
  },
  set_entity_feedback: async (body) =>
    client.longTerm.setEntityFeedback(body["entity_id"] as string, {
      userScore: body["user_score"] as number,
      confirmed: (body["confirmed"] as boolean) ?? false,
    }),
  get_entity_history: async (body) =>
    client.longTerm.getEntityHistory(body["entity_id"] as string),
  merge_entities: async (body) =>
    client.longTerm.mergeEntities(body["source_id"] as string, body["target_id"] as string),
  get_entity_graph: async () => client.longTerm.getEntityGraph(),

  record_step: async (body) =>
    client.reasoning.recordStep({
      conversationId: body["conversation_id"] as string,
      reasoning: body["reasoning"] as string,
      actionTaken: body["action_taken"] as string,
      result: body["result"] as string | undefined,
    }),
  list_steps: async (body) =>
    client.reasoning.listSteps(body["conversation_id"] as string),
  explain_step: async (body) => client.reasoning.explainStep(body["step_id"] as string),
  get_trace_by_conversation: async (body) =>
    client.reasoning.getTraceByConversation(body["conversation_id"] as string),
  get_entity_provenance: async (body) =>
    client.reasoning.getEntityProvenance(body["entity_id"] as string),
  cypher_query: async (body) =>
    client.query.cypher({
      cypher: body["cypher"] as string,
      params: body["params"] as Record<string, unknown> | undefined,
    }),
};

const server = createServer(async (req, res) => {
  if (req.method !== "POST") {
    res.writeHead(405);
    res.end();
    return;
  }

  const method = req.url?.replace(/^\//, "") ?? "";
  const handler = handlers[method];

  if (!handler) {
    jsonResponse(res, { error: `Unknown method: ${method}` }, 404);
    return;
  }

  try {
    const body = await readBody(req);
    const result = await handler(body);
    if (result === undefined) noContent(res);
    else jsonResponse(res, result);
  } catch (error) {
    // The conformance server is a dev-only tool: log the full error
    // (including any stack) to stderr where the operator can read it, but
    // return a generic message to the caller so we don't leak internals
    // (file paths, server config) over the wire.
    console.error("[conformance]", method, error);
    jsonResponse(res, { error: `${method} failed` }, 500);
  }
});

server.listen(PORT, () => {
  console.log(`TypeScript conformance server running on http://localhost:${PORT}`);
  console.log(`Upstream: ${UPSTREAM}`);
  console.log("Press Ctrl+C to stop");
});
