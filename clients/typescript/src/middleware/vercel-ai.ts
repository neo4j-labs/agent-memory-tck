/**
 * Vercel AI SDK middleware for automatic memory integration.
 *
 * The middleware automatically:
 *   - Injects three-tier conversational context (reflections + observations +
 *     recent messages) ahead of every model call when a `conversationId` is
 *     supplied — falls back to flat history for bridge transports.
 *   - Persists the user's input message before generation.
 *   - Persists the assistant's response (and tool calls) after generation.
 *   - Lazily creates a conversation on first call when the caller didn't
 *     pre-create one (only available with RestTransport).
 *
 * @example
 * ```ts
 * import { generateText } from "ai";
 * import { MemoryClient } from "@neo4j-labs/agent-memory";
 * import { agentMemoryMiddleware } from "@neo4j-labs/agent-memory/middleware/vercel-ai";
 *
 * const client = new MemoryClient({
 *   endpoint: "https://memory.neo4jlabs.com/v1",
 *   apiKey: process.env.MEMORY_API_KEY!,
 * });
 *
 * const middleware = agentMemoryMiddleware(client, {
 *   conversationId: "conv-uuid",      // or sessionId for bridge transport
 *   userId: "alice@example.com",      // used if a conversation is created
 *   includeContext: true,
 * });
 *
 * const result = await generateText({
 *   model: yourModel,
 *   experimental_middleware: middleware,
 *   messages: [{ role: "user", content: "Hello!" }],
 * });
 * ```
 *
 * Compatible with the Vercel AI SDK v4+ `LanguageModelV1Middleware` shape.
 */

import type { MemoryClient } from "../client.js";
import { NotSupportedError } from "../errors.js";
import type { Message, MessageRole } from "../types.js";

export interface AgentMemoryMiddlewareOptions {
  /**
   * Conversation id (REST transport) or session id (bridge transport).
   * Can be a string or a function that returns one.
   */
  conversationId?: string | (() => string);

  /** @deprecated Use `conversationId`. Kept for backwards compatibility. */
  sessionId?: string | (() => string);

  /**
   * User id used when lazily creating a conversation. Only consulted if
   * `conversationId` is not supplied and the transport is REST.
   */
  userId?: string;

  /**
   * Include three-tier context (reflections + observations + recent messages).
   * If false, falls back to flat history. Default: true on REST, false on
   * bridge (where context endpoints aren't implemented).
   */
  includeContext?: boolean;

  /** Maximum messages to include from flat history (bridge fallback). */
  historyLimit?: number;

  /** Persist user input before generation. Default: true. */
  persistInput?: boolean;

  /** Persist assistant response after generation. Default: true. */
  persistResponses?: boolean;
}

export interface AgentMemoryLanguageModelMiddleware {
  transformParams?: (options: { params: Record<string, unknown> }) => Promise<
    Record<string, unknown>
  >;
  wrapGenerate?: (options: {
    doGenerate: () => Promise<{ text?: string; [key: string]: unknown }>;
  }) => Promise<{ text?: string; [key: string]: unknown }>;
}

function resolve(value?: string | (() => string)): string | undefined {
  if (typeof value === "function") return value();
  return value;
}

/** Memory-augmented LanguageModelV1 middleware. */
export function agentMemoryMiddleware(
  client: MemoryClient,
  options?: AgentMemoryMiddlewareOptions,
): AgentMemoryLanguageModelMiddleware {
  const persistResponses = options?.persistResponses ?? true;
  const persistInput = options?.persistInput ?? true;
  const includeContext = options?.includeContext ?? true;
  let resolvedId: string | undefined =
    resolve(options?.conversationId) ?? resolve(options?.sessionId);

  // Lazy-create a conversation on REST transports if none was supplied.
  async function ensureConversationId(): Promise<string> {
    if (resolvedId) return resolvedId;
    try {
      const conv = await client.shortTerm.createConversation({
        userId: options?.userId ?? "anonymous",
      });
      resolvedId = conv.id;
      return resolvedId;
    } catch (err) {
      if (err instanceof NotSupportedError) {
        // Bridge transport — synthesize a session ID
        resolvedId = `session-${cryptoRandom()}`;
        return resolvedId;
      }
      throw err;
    }
  }

  return {
    transformParams: async ({ params }) => {
      const id = await ensureConversationId();

      let historyMessages: Array<{ role: string; content: string }> = [];
      if (includeContext) {
        try {
          const ctx = await client.shortTerm.getContext(id);
          for (const r of ctx.reflections) {
            historyMessages.push({ role: "system", content: `[reflection] ${r.content}` });
          }
          for (const o of ctx.observations) {
            historyMessages.push({ role: "system", content: `[observation] ${o.content}` });
          }
          for (const m of ctx.recentMessages) {
            historyMessages.push({ role: m.role, content: m.content });
          }
        } catch (err) {
          if (!(err instanceof NotSupportedError)) {
            // Non-fatal — fall back to flat history below.
          }
          historyMessages = [];
        }
      }

      // Bridge fallback or empty REST context → use flat conversation history.
      if (historyMessages.length === 0) {
        try {
          const conv = await client.shortTerm.getConversation(id, {
            limit: options?.historyLimit,
          });
          historyMessages = conv.messages.map((msg: Message) => ({
            role: msg.role,
            content: msg.content,
          }));
        } catch {
          // No history — proceed without.
        }
      }

      // Best-effort: persist the user's input message.
      if (persistInput) {
        const incoming = (params["prompt"] as Array<{ role: string; content: string }>) ?? [];
        const lastUser = [...incoming].reverse().find((m) => m.role === "user");
        if (lastUser) {
          try {
            await client.shortTerm.addMessage(id, lastUser.role as MessageRole, lastUser.content);
          } catch {
            // Non-fatal.
          }
        }
      }

      if (historyMessages.length === 0) return params;

      const existing = (params["prompt"] as unknown[]) ?? [];
      return {
        ...params,
        prompt: [...historyMessages, ...existing],
      };
    },

    wrapGenerate: async ({ doGenerate }) => {
      const result = await doGenerate();
      if (persistResponses && result.text) {
        const id = await ensureConversationId();
        try {
          await client.shortTerm.addMessage(id, "assistant", result.text);
        } catch {
          // Non-fatal.
        }
      }
      return result;
    },
  };
}

function cryptoRandom(): string {
  // Every supported runtime (Node 20+, Bun, Deno, Workers, modern browsers)
  // exposes crypto.randomUUID. We fall back to crypto.getRandomValues with
  // base36 encoding for older or stripped-down environments — both APIs use
  // the platform CSPRNG. Math.random would be cryptographically insecure.
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const buf = new Uint8Array(16);
    crypto.getRandomValues(buf);
    return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
  }
  throw new Error(
    "Secure randomness is unavailable in this runtime; supply an explicit conversationId.",
  );
}
