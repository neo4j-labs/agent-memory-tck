import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const timestamp = "2026-03-21T10:00:00Z";
// Collide with model and collection names inside opaque user data.
const opaque = {
  sessionId: "user-owned", createdAt: "unchanged", snake_case: true,
  nestedValue: [{ toolCalls: [{ durationMs: 12 }], messages: { conversationId: "opaque" } }],
};
const message = {
  id: "m1", role: "user", content: "hello", timestamp,
  conversation_id: "c1", metadata: opaque,
};
const conversation = {
  id: "c1", session_id: "session", created_at: timestamp, updated_at: timestamp,
  message_count: 1, workspace_id: "w1", user_id: "u1", messages: [message], metadata: opaque,
};
const entity = {
  id: "e1", name: "Ada", type: "PERSON", canonical_name: "Ada",
  created_at: timestamp, updated_at: timestamp, source_stage: "extraction",
  relationships: [{ id: "r1", type: "KNOWS", target_id: "e2", target_name: "Grace", properties: opaque }],
};
const tool = {
  id: "tool1", tool_name: "lookup", arguments: opaque, result: opaque,
  status: "success", duration_ms: 12,
};
const trace = {
  id: "t1", session_id: "session", task: "lookup", started_at: timestamp, completed_at: timestamp,
  steps: [{ id: "s1", trace_id: "t1", step_number: 1, tool_calls: [tool] }],
};
const responses: Record<string, unknown> = {
  get_conversation: conversation,
  list_sessions: [{ session_id: "session", message_count: 1, created_at: timestamp, updated_at: timestamp }],
  add_message: message,
  search_messages: [message],
  get_entity: entity,
  search_entities: [entity],
  get_entity_by_name: null,
  get_trace_with_steps: trace,
  record_tool_call: tool,
  add_relationship: { id: "r1", source_id: "e1", target_id: "e2", relationship_type: "KNOWS", properties: opaque },
  cypher_query: { columns: ["userData"], rows: [[opaque]], stats: opaque },
};
let upstream: Server;
let bridge: ChildProcess;
let bridgeUrl: string;

async function listen(server: Server): Promise<number> {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return (server.address() as AddressInfo).port;
}
async function post(method: string, body: Record<string, unknown> = {}): Promise<Response> {
  return fetch(`${bridgeUrl}/${method}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
}

beforeAll(async () => {
  upstream = createServer(async (req, res) => {
    for await (const _chunk of req) { /* drain request */ }
    const method = req.url!.slice(1);
    if (!(method in responses)) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(responses[method]));
  });
  const upstreamPort = await listen(upstream);
  const reservation = createServer();
  const bridgePort = await listen(reservation);
  await new Promise<void>((resolve) => reservation.close(() => resolve()));
  bridgeUrl = `http://127.0.0.1:${bridgePort}`;
  bridge = spawn(process.execPath, ["--import", "tsx", "conformance/server.ts"], {
    cwd: process.cwd(),
    env: { ...process.env, MEMORY_ENDPOINT: `http://127.0.0.1:${upstreamPort}`, MEMORY_API_KEY: "", TCK_BRIDGE_PORT: String(bridgePort) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await new Promise<void>((resolve, reject) => {
    let output = "";
    const timeout = setTimeout(() => reject(new Error(`Bridge startup timed out: ${output}`)), 10_000);
    bridge.stdout!.on("data", (data: Buffer) => {
      output += data.toString();
      if (output.includes("conformance server running")) {
        clearTimeout(timeout);
        resolve();
      }
    });
    bridge.stderr!.on("data", (data: Buffer) => { output += data.toString(); });
    bridge.once("error", reject);
    bridge.once("exit", (code) => {
      clearTimeout(timeout);
      reject(new Error(`Bridge exited (${code}): ${output}`));
    });
  });
});
afterAll(async () => {
  if (bridge && bridge.exitCode === null) {
    const exited = once(bridge, "exit");
    bridge.kill();
    await exited;
  }
  if (upstream) await new Promise<void>((resolve) => upstream.close(() => resolve()));
});

describe("conformance model serialization over public HTTP", () => {
  it.each([
    ["get_conversation", { session_id: "session" }, conversation],
    ["list_sessions", {}, responses.list_sessions],
    ["add_message", { session_id: "session", role: "user", content: "hello", metadata: opaque }, message],
    ["search_messages", { query: "hello" }, [message]],
    ["get_entity", { entity_id: "e1" }, entity],
    ["search_entities", { query: "Ada" }, [entity]],
    ["get_entity_by_name", { name: "missing" }, null],
    ["get_trace_with_steps", { trace_id: "t1" }, trace],
    ["record_tool_call", { step_id: "s1", tool_name: "lookup", arguments: opaque, result: opaque }, tool],
    ["add_relationship", { source_id: "e1", target_id: "e2", relationship_type: "KNOWS", properties: opaque }, responses.add_relationship],
    ["cypher_query", { cypher: "RETURN $userData", params: { userData: opaque } }, responses.cypher_query],
  ] as const)("%s returns bridge fields and preserves opaque user keys", async (method, body, expected) => {
    const response = await post(method, body);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(expected);
  });
});
