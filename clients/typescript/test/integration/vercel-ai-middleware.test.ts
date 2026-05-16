/**
 * Smoke test — Vercel AI middleware drives transformParams / wrapGenerate
 * end-to-end against an MSW-mocked hosted service.
 *
 * Asserts the four contract points that adopters depend on:
 *  - user input is persisted before generation
 *  - context (or flat history) is injected into the prompt
 *  - assistant output is persisted after generation
 *  - wrapGenerate forwards the doGenerate result unchanged
 */

import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MemoryClient } from "../../src/client.js";
import { agentMemoryMiddleware } from "../../src/middleware/vercel-ai.js";

const ENDPOINT = "https://memory.test/v1";
const API_KEY = "nams_test_key";
const CONV_ID = "conv-vercel-ai";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

interface CapturedMessage {
  role: string;
  content: string;
}

describe("Vercel AI middleware — smoke test", () => {
  it("injects context, persists user input, and persists assistant output", async () => {
    const persistedMessages: CapturedMessage[] = [];

    server.use(
      // Three-tier context endpoint
      http.get(`${ENDPOINT}/conversations/${CONV_ID}/context`, () =>
        HttpResponse.json({
          reflections: [
            { id: "r1", conversationId: CONV_ID, content: "user values concise replies", createdAt: "x" },
          ],
          observations: [
            { id: "o1", conversationId: CONV_ID, content: "asks about Neo4j", createdAt: "x" },
          ],
          recentMessages: [{ id: "m1", role: "user", content: "previously: hello" }],
        }),
      ),
      // Add-message endpoint — record what got persisted
      http.post(`${ENDPOINT}/conversations/${CONV_ID}/messages`, async ({ request }) => {
        const body = (await request.json()) as CapturedMessage;
        persistedMessages.push(body);
        return HttpResponse.json({
          id: `m-${persistedMessages.length}`,
          role: body.role,
          content: body.content,
        });
      }),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    const middleware = agentMemoryMiddleware(client, {
      conversationId: CONV_ID,
      includeContext: true,
    });

    // Driver: simulate the shape Vercel AI's middleware contract uses.
    const incomingPrompt = [{ role: "user", content: "Tell me about graphs." }];
    const transformed = await middleware.transformParams!({
      params: { prompt: incomingPrompt, model: "stub" },
    });

    // 1. Context was prepended — system messages from reflections/observations,
    //    plus the recent message, then the incoming user prompt at the end.
    const transformedPrompt = transformed["prompt"] as CapturedMessage[];
    expect(transformedPrompt.length).toBeGreaterThan(incomingPrompt.length);
    const allContent = transformedPrompt.map((m) => m.content).join("\n");
    expect(allContent).toContain("[reflection] user values concise replies");
    expect(allContent).toContain("[observation] asks about Neo4j");
    expect(allContent).toContain("previously: hello");
    expect(allContent).toContain("Tell me about graphs.");

    // 2. User input was persisted.
    expect(persistedMessages.find((m) => m.role === "user" && m.content === "Tell me about graphs.")).toBeDefined();

    // 3. wrapGenerate forwards the model result and persists assistant text.
    const fakeResult = { text: "Graphs model relationships.", finishReason: "stop" };
    const wrapped = await middleware.wrapGenerate!({
      doGenerate: async () => fakeResult,
    });
    expect(wrapped).toEqual(fakeResult);

    // 4. Assistant message was persisted.
    expect(
      persistedMessages.find(
        (m) => m.role === "assistant" && m.content === "Graphs model relationships.",
      ),
    ).toBeDefined();
  });

  it("falls back to flat history when context fetch fails", async () => {
    let contextCalled = false;
    let historyCalled = false;

    server.use(
      http.get(`${ENDPOINT}/conversations/${CONV_ID}/context`, () => {
        contextCalled = true;
        return new HttpResponse("nope", { status: 500 });
      }),
      http.get(`${ENDPOINT}/conversations/${CONV_ID}/messages`, () => {
        historyCalled = true;
        return HttpResponse.json({
          messages: [{ id: "m1", role: "user", content: "earlier flat message" }],
        });
      }),
      http.post(`${ENDPOINT}/conversations/${CONV_ID}/messages`, () =>
        HttpResponse.json({ id: "x", role: "user", content: "" }),
      ),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    const middleware = agentMemoryMiddleware(client, {
      conversationId: CONV_ID,
      includeContext: true,
    });

    const transformed = await middleware.transformParams!({
      params: { prompt: [{ role: "user", content: "now" }] },
    });

    expect(contextCalled).toBe(true);
    expect(historyCalled).toBe(true);
    const transformedPrompt = transformed["prompt"] as CapturedMessage[];
    expect(transformedPrompt.some((m) => m.content === "earlier flat message")).toBe(true);
  });

  it("skips persistence when persistInput=false and persistResponses=false", async () => {
    const persistedMessages: CapturedMessage[] = [];

    server.use(
      http.get(`${ENDPOINT}/conversations/${CONV_ID}/context`, () =>
        HttpResponse.json({ reflections: [], observations: [], recentMessages: [] }),
      ),
      http.get(`${ENDPOINT}/conversations/${CONV_ID}/messages`, () =>
        HttpResponse.json({ messages: [] }),
      ),
      http.post(`${ENDPOINT}/conversations/${CONV_ID}/messages`, async ({ request }) => {
        const body = (await request.json()) as CapturedMessage;
        persistedMessages.push(body);
        return HttpResponse.json({ id: "x", role: body.role, content: body.content });
      }),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    const middleware = agentMemoryMiddleware(client, {
      conversationId: CONV_ID,
      includeContext: true,
      persistInput: false,
      persistResponses: false,
    });

    await middleware.transformParams!({ params: { prompt: [{ role: "user", content: "x" }] } });
    await middleware.wrapGenerate!({ doGenerate: async () => ({ text: "y" }) });

    expect(persistedMessages).toHaveLength(0);
  });
});
