/**
 * Integration tests — User-Agent header, request-id propagation, and the
 * logger event stream against an MSW-mocked RestTransport.
 */

import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MemoryClient } from "../../src/client.js";
import {
  AuthenticationError,
  MemoryError,
  TransportError,
} from "../../src/errors.js";
import type { LogEvent } from "../../src/observability.js";
import { VERSION } from "../../src/version.js";

const ENDPOINT = "https://memory.test/v1";
const API_KEY = "nams_test_key";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("RestTransport observability", () => {
  it("sends a default User-Agent header containing the package version", async () => {
    let observedUA: string | null = null;
    server.use(
      http.post(`${ENDPOINT}/conversations`, ({ request }) => {
        observedUA = request.headers.get("user-agent");
        return HttpResponse.json({ id: "c", userId: "u", createdAt: "x" });
      }),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    await client.shortTerm.createConversation({ userId: "u" });

    expect(observedUA).toMatch(/@neo4j-labs\/agent-memory\/\d+\.\d+\.\d+/);
    expect(observedUA).toContain(VERSION);
  });

  it("caller-supplied User-Agent header takes precedence", async () => {
    let observedUA: string | null = null;
    server.use(
      http.post(`${ENDPOINT}/conversations`, ({ request }) => {
        observedUA = request.headers.get("user-agent");
        return HttpResponse.json({ id: "c", userId: "u", createdAt: "x" });
      }),
    );

    const client = new MemoryClient({
      endpoint: ENDPOINT,
      apiKey: API_KEY,
      headers: { "User-Agent": "my-wrapper/1.0" },
    });
    await client.shortTerm.createConversation({ userId: "u" });

    expect(observedUA).toBe("my-wrapper/1.0");
  });

  it("attaches requestId from x-request-id to TransportError on 5xx", async () => {
    server.use(
      http.post(`${ENDPOINT}/conversations`, () =>
        HttpResponse.json(
          { error: "kaboom" },
          { status: 500, headers: { "x-request-id": "req-xyz" } },
        ),
      ),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    try {
      await client.shortTerm.createConversation({ userId: "u" });
      throw new Error("expected throw");
    } catch (e) {
      expect(e).toBeInstanceOf(TransportError);
      expect((e as MemoryError).requestId).toBe("req-xyz");
      expect(String(e)).toContain("req-xyz");
    }
  });

  it("attaches requestId to AuthenticationError on 401", async () => {
    server.use(
      http.post(`${ENDPOINT}/conversations`, () =>
        new HttpResponse("nope", {
          status: 401,
          headers: { "x-request-id": "req-auth" },
        }),
      ),
    );

    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    try {
      await client.shortTerm.createConversation({ userId: "u" });
      throw new Error("expected throw");
    } catch (e) {
      expect(e).toBeInstanceOf(AuthenticationError);
      expect((e as MemoryError).requestId).toBe("req-auth");
    }
  });

  it("emits request → response logger events on success", async () => {
    server.use(
      http.post(`${ENDPOINT}/conversations`, () =>
        HttpResponse.json(
          { id: "c", userId: "u", createdAt: "x" },
          { headers: { "x-request-id": "req-ok" } },
        ),
      ),
    );

    const events: LogEvent[] = [];
    const client = new MemoryClient({
      endpoint: ENDPOINT,
      apiKey: API_KEY,
      logger: (e) => events.push(e),
    });
    await client.shortTerm.createConversation({ userId: "u" });

    expect(events).toHaveLength(2);
    expect(events[0]!.kind).toBe("request");
    expect(events[1]!.kind).toBe("response");
    if (events[1]!.kind === "response") {
      expect(events[1].status).toBe(200);
      expect(events[1].requestId).toBe("req-ok");
      expect(events[1].durationMs).toBeGreaterThanOrEqual(0);
    }
  });

  it("emits request → error logger event on failure", async () => {
    server.use(
      http.post(`${ENDPOINT}/conversations`, () =>
        HttpResponse.json(
          { error: "boom" },
          { status: 500, headers: { "x-request-id": "req-err" } },
        ),
      ),
    );

    const events: LogEvent[] = [];
    const logger = vi.fn((e: LogEvent) => events.push(e));
    const client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY, logger });
    await expect(client.shortTerm.createConversation({ userId: "u" })).rejects.toBeInstanceOf(
      TransportError,
    );

    expect(events).toHaveLength(2);
    expect(events[1]!.kind).toBe("error");
    if (events[1]!.kind === "error") {
      expect(events[1].status).toBe(500);
      expect(events[1].requestId).toBe("req-err");
    }
  });

  it("logger exceptions never propagate", async () => {
    server.use(
      http.post(`${ENDPOINT}/conversations`, () =>
        HttpResponse.json({ id: "c", userId: "u", createdAt: "x" }),
      ),
    );

    const client = new MemoryClient({
      endpoint: ENDPOINT,
      apiKey: API_KEY,
      logger: () => {
        throw new Error("logger boom");
      },
    });

    await expect(client.shortTerm.createConversation({ userId: "u" })).resolves.toMatchObject({
      id: "c",
    });
  });
});
