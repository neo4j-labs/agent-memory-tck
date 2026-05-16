/**
 * Integration tests — MCP tool dispatch routes correctly through MemoryClient.
 */

import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MemoryClient } from "../../src/client.js";
import { handleMemoryToolCall } from "../../src/mcp/index.js";

const ENDPOINT = "https://memory.test/v1";

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("handleMemoryToolCall", () => {
  it("memory_create_conversation routes to POST /conversations", async () => {
    let body: unknown = null;
    server.use(
      http.post(`${ENDPOINT}/conversations`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ id: "c1", userId: "alice", createdAt: "x" });
      }),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: "k" });
    const result = await handleMemoryToolCall(client, "memory_create_conversation", {
      user_id: "alice",
    });
    await client.close();

    expect(body).toMatchObject({ userId: "alice" });
    expect((result as { id: string }).id).toBe("c1");
  });

  it("memory_get_context routes to GET /conversations/{id}/context", async () => {
    server.use(
      http.get(`${ENDPOINT}/conversations/c1/context`, () =>
        HttpResponse.json({
          reflections: [],
          observations: [],
          recentMessages: [{ id: "m1", role: "user", content: "hi" }],
        }),
      ),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: "k" });
    const result = (await handleMemoryToolCall(client, "memory_get_context", {
      conversation_id: "c1",
    })) as { recentMessages: unknown[] };
    await client.close();

    expect(result.recentMessages).toHaveLength(1);
  });

  it("rejects unknown tool names", async () => {
    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: "k" });
    await expect(
      handleMemoryToolCall(client, "memory_unknown_tool", {}),
    ).rejects.toThrow(/Unknown memory tool/);
    await client.close();
  });

  it("v0.1 alias forwards to new tool with deprecation warning", async () => {
    server.use(
      http.get(`${ENDPOINT}/conversations/c1/context`, () =>
        HttpResponse.json({ reflections: [], observations: [], recentMessages: [] }),
      ),
    );

    const warn = console.warn;
    const messages: string[] = [];
    console.warn = (msg: string) => messages.push(String(msg));

    try {
      const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: "k" });
      await handleMemoryToolCall(client, "memory.getConversation", {
        conversation_id: "c1",
      });
      await client.close();

      expect(messages.join("\n")).toMatch(/deprecated/i);
    } finally {
      console.warn = warn;
    }
  });
});
