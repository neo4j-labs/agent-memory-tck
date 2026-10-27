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
let hostedBridge: ChildProcess;
let hostedUrl: string;
const upstreamPaths: string[] = [];
let cleanupStatus = 204;
const cleanupRequests: Array<{ method: string; body: unknown }> = [];

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
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    upstreamPaths.push(req.url!);
    if (req.url === "/v1/entities/e1" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ id: "e1", name: "Ada", type: "person", canonicalName: "Ada", createdAt: timestamp }));
      return;
    }
    const method = req.url!.slice(1);
    if (method === "clear_all_data") {
      cleanupRequests.push({ method: req.method!, body: JSON.parse(Buffer.concat(chunks).toString()) });
      res.writeHead(cleanupStatus, { "Content-Type": "application/json" });
      res.end(cleanupStatus === 204 ? undefined : JSON.stringify({ error: "cleanup unavailable" }));
      return;
    }
    if (!(method in responses)) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(responses[method]));
  });
  const upstreamPort = await listen(upstream);
  const endpoint = `http://127.0.0.1:${upstreamPort}`;
  ({ child: bridge, url: bridgeUrl } = await startBridge(endpoint));
  ({ child: hostedBridge, url: hostedUrl } = await startBridge(`${endpoint}/v1`));
});

async function startBridge(endpoint: string): Promise<{ child: ChildProcess; url: string }> {
  const reservation = createServer();
  const bridgePort = await listen(reservation);
  await new Promise<void>((resolve) => reservation.close(() => resolve()));
  const url = `http://127.0.0.1:${bridgePort}`;
  const child = spawn(process.execPath, ["--import", "tsx", "conformance/server.ts"], {
    cwd: process.cwd(),
    env: { ...process.env, MEMORY_ENDPOINT: endpoint, MEMORY_API_KEY: "", TCK_BRIDGE_PORT: String(bridgePort) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await new Promise<void>((resolve, reject) => {
    let output = "";
    const timeout = setTimeout(() => reject(new Error(`Bridge startup timed out: ${output}`)), 10_000);
    child.stdout!.on("data", (data: Buffer) => {
      output += data.toString();
      if (output.includes("conformance server running")) {
        clearTimeout(timeout);
        resolve();
      }
    });
    child.stderr!.on("data", (data: Buffer) => { output += data.toString(); });
    child.once("error", reject);
    child.once("exit", (code) => {
      clearTimeout(timeout);
      reject(new Error(`Bridge exited (${code}): ${output}`));
    });
  });
  return { child, url };
}

afterAll(async () => {
  for (const child of [bridge, hostedBridge]) {
    if (child && child.exitCode === null) {
      const exited = once(child, "exit");
      child.kill();
      await exited;
    }
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

describe("conformance cleanup over public HTTP", () => {
  it("forwards clear_all_data upstream and returns an empty 204 response", async () => {
    cleanupRequests.length = 0;
    cleanupStatus = 204;
    const response = await post("clear_all_data");
    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");
    expect(cleanupRequests).toEqual([{ method: "POST", body: {} }]);
  });

  it("reports upstream cleanup failures instead of claiming success", async () => {
    cleanupRequests.length = 0;
    cleanupStatus = 503;
    try {
      const response = await post("clear_all_data");
      expect(response.status).toBe(500);
      expect(await response.json()).toEqual({ error: "clear_all_data failed" });
      expect(cleanupRequests).toEqual([{ method: "POST", body: {} }]);
    } finally {
      cleanupStatus = 204;
    }
  });
});


describe("hosted conformance transport selection", () => {
  it("keeps REST model requests and the existing hosted cleanup behavior", async () => {
    const entityResponse = await fetch(`${hostedUrl}/get_entity`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ entity_id: "e1" }),
    });
    expect(entityResponse.status).toBe(200);
    expect(await entityResponse.json()).toEqual({
      id: "e1", name: "Ada", type: "person", canonical_name: "Ada", created_at: timestamp,
    });
    expect(upstreamPaths).toContain("/v1/entities/e1");
    const requestsBeforeCleanup = upstreamPaths.length;
    const cleanupResponse = await fetch(`${hostedUrl}/clear_all_data`, { method: "POST" });
    expect(cleanupResponse.status).toBe(204);
    expect(await cleanupResponse.text()).toBe("");
    expect(upstreamPaths).toHaveLength(requestsBeforeCleanup);
  });
});
