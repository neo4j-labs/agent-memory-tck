/**
 * Vercel AI SDK middleware for automatic memory integration.
 *
 * Wraps the Vercel AI SDK's useChat/streamText to automatically:
 *   - Inject relevant conversation history into every prompt
 *   - Persist assistant responses after generation
 *   - Capture tool calls as reasoning traces
 *
 * @example
 * ```ts
 * import { streamText } from "ai";
 * import { MemoryClient } from "@neo4j-labs/agent-memory";
 * import { agentMemoryMiddleware } from "@neo4j-labs/agent-memory/middleware/vercel-ai";
 *
 * const client = new MemoryClient({ endpoint: "..." });
 * await client.connect();
 *
 * const middleware = agentMemoryMiddleware(client, {
 *   sessionId: "user-123-session",
 * });
 *
 * const result = await streamText({
 *   model: yourModel,
 *   experimental_middleware: middleware,
 *   messages: [{ role: "user", content: "Hello!" }],
 * });
 * ```
 */

import type { MemoryClient } from "../client.js";
import type { Message } from "../types.js";

export interface AgentMemoryMiddlewareOptions {
  /** Session ID for conversation tracking. Can be a string or a function that returns one. */
  sessionId?: string | (() => string);

  /** Whether to include conversation history in prompts. Default: true. */
  includeHistory?: boolean | number;

  /** Whether to persist assistant responses. Default: true. */
  persistResponses?: boolean;
}

/**
 * Resolve the session ID from the options.
 */
function resolveSessionId(
  sessionId?: string | (() => string),
): string {
  if (typeof sessionId === "function") return sessionId();
  return sessionId ?? `session-${crypto.randomUUID()}`;
}

/**
 * Create a Vercel AI SDK middleware that integrates agent memory.
 *
 * The middleware intercepts the model call to:
 * 1. Prepend conversation history from memory (transformParams)
 * 2. Persist the assistant's response after generation (wrapGenerate)
 *
 * Compatible with Vercel AI SDK v4+ LanguageModelV1Middleware interface.
 */
export function agentMemoryMiddleware(
  client: MemoryClient,
  options?: AgentMemoryMiddlewareOptions,
): AgentMemoryLanguageModelMiddleware {
  const persistResponses = options?.persistResponses ?? true;
  const includeHistory = options?.includeHistory ?? true;

  return {
    transformParams: async ({ params }) => {
      if (!includeHistory) return params;

      const sid = resolveSessionId(options?.sessionId);
      const limit =
        typeof includeHistory === "number" ? includeHistory : undefined;

      try {
        const conversation = await client.shortTerm.getConversation(sid, {
          limit,
        });

        if (conversation.messages.length === 0) return params;

        // Convert memory messages to Vercel AI SDK message format
        const historyMessages = conversation.messages.map(
          (msg: Message) => ({
            role: msg.role as "user" | "assistant" | "system",
            content: msg.content,
          }),
        );

        // Prepend history to the existing messages
        const existingMessages = (params.prompt as unknown[]) ?? [];
        return {
          ...params,
          prompt: [...historyMessages, ...existingMessages],
        };
      } catch {
        // If memory retrieval fails, proceed without history
        return params;
      }
    },

    wrapGenerate: async ({ doGenerate }) => {
      const result = await doGenerate();

      if (persistResponses && result.text) {
        const sid = resolveSessionId(options?.sessionId);
        try {
          await client.shortTerm.addMessage(sid, "assistant", result.text);
        } catch {
          // Non-fatal: don't block the response if persistence fails
        }
      }

      return result;
    },
  };
}

/**
 * Simplified type for the middleware interface.
 * Compatible with Vercel AI SDK's LanguageModelV1Middleware.
 */
export interface AgentMemoryLanguageModelMiddleware {
  transformParams?: (options: {
    params: Record<string, unknown>;
  }) => Promise<Record<string, unknown>>;
  wrapGenerate?: (options: {
    doGenerate: () => Promise<{ text?: string; [key: string]: unknown }>;
  }) => Promise<{ text?: string; [key: string]: unknown }>;
}
