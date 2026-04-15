/**
 * HTTP Bridge Conformance Server for the TypeScript client.
 *
 * This server implements the TCK HTTP bridge protocol, enabling the
 * Python TCK test suite to validate the TypeScript client.
 *
 * Usage:
 *   MEMORY_ENDPOINT=https://... tsx conformance/server.ts
 *   # Then from the TCK repo:
 *   pytest -m bronze --bridge-url http://localhost:3001
 */

import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { MemoryClient } from "../src/client.js";

const PORT = parseInt(process.env["TCK_BRIDGE_PORT"] ?? "3001", 10);
const UPSTREAM = process.env["MEMORY_ENDPOINT"] ?? process.env["NEO4J_URI"] ?? "";

if (!UPSTREAM) {
  console.error(
    "Set MEMORY_ENDPOINT (hosted service URL) or NEO4J_URI (direct connection) env var.",
  );
  process.exit(1);
}

const client = new MemoryClient({ endpoint: UPSTREAM });

async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(chunk as Buffer);
  }
  const text = Buffer.concat(chunks).toString("utf-8");
  if (!text) return {};
  return JSON.parse(text) as Record<string, unknown>;
}

function jsonResponse(res: ServerResponse, data: unknown, status = 200): void {
  const body = JSON.stringify(data);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(body);
}

function noContent(res: ServerResponse): void {
  res.writeHead(204);
  res.end();
}

type Handler = (body: Record<string, unknown>) => Promise<unknown>;

const handlers: Record<string, Handler> = {
  // Lifecycle
  setup: async () => ({ ok: true, protocol_version: "0.1.0" }),
  teardown: async () => undefined,
  clear_all_data: async () => {
    // The bridge server relies on the upstream to handle this
    // For direct Neo4j, this would clear the database
    return undefined;
  },

  // Short-Term Memory
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
    client.shortTerm.listSessions({
      limit: (body["limit"] as number) ?? 100,
    }),

  delete_message: async (body) => ({
    deleted: await client.shortTerm.deleteMessage(body["message_id"] as string),
  }),

  clear_session: async (body) => {
    await client.shortTerm.clearSession(body["session_id"] as string);
    return undefined;
  },

  // Long-Term Memory
  add_entity: async (body) =>
    client.longTerm.addEntity(
      body["name"] as string,
      body["entity_type"] as string,
      { description: body["description"] as string | undefined },
    ),

  add_preference: async (body) =>
    client.longTerm.addPreference(
      body["category"] as string,
      body["preference"] as string,
      { context: body["context"] as string | undefined },
    ),

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

  // Reasoning Memory
  start_trace: async (body) =>
    client.reasoning.startTrace(
      body["session_id"] as string,
      body["task"] as string,
    ),

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

  // Gold Tier
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

    if (result === undefined) {
      noContent(res);
    } else {
      jsonResponse(res, result);
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    jsonResponse(res, { error: message }, 500);
  }
});

server.listen(PORT, () => {
  console.log(`TypeScript conformance server running on http://localhost:${PORT}`);
  console.log(`Upstream: ${UPSTREAM}`);
  console.log("Press Ctrl+C to stop");
});
