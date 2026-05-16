/**
 * Mastra integration — wraps `MemoryClient` as a Mastra-compatible memory
 * provider. Mastra's `Memory` interface is duck-typed here to avoid a hard
 * dependency on `@mastra/core`.
 *
 * @example
 * ```ts
 * import { MemoryClient } from "@neo4j-labs/agent-memory";
 * import { Neo4jMastraMemory } from "@neo4j-labs/agent-memory/integrations/mastra";
 * import { Agent } from "@mastra/core/agent";
 *
 * const client = new MemoryClient({ endpoint: "...", apiKey: "..." });
 * const memory = new Neo4jMastraMemory(client);
 *
 * const agent = new Agent({ name: "scout", memory });
 * ```
 */

import type { MemoryClient } from "../client.js";
import { NotSupportedError } from "../errors.js";

export interface MastraThread {
  id: string;
  resourceId: string;
  title?: string;
  metadata?: Record<string, unknown>;
}

export interface MastraMemoryMessage {
  id: string;
  threadId: string;
  role: "user" | "assistant" | "system";
  content: string;
  createdAt: string;
}

export class Neo4jMastraMemory {
  constructor(private readonly client: MemoryClient) {}

  /** Create a new thread (Mastra term) — backed by createConversation on REST. */
  async createThread(input: {
    resourceId: string;
    title?: string;
    metadata?: Record<string, unknown>;
  }): Promise<MastraThread> {
    try {
      const conv = await this.client.shortTerm.createConversation({
        userId: input.resourceId,
        metadata: { ...input.metadata, mastraTitle: input.title },
      });
      return {
        id: conv.id,
        resourceId: input.resourceId,
        title: input.title,
        metadata: conv.metadata,
      };
    } catch (err) {
      if (err instanceof NotSupportedError) {
        // Bridge transport: thread id == sessionId from the caller.
        return { id: input.resourceId, resourceId: input.resourceId, title: input.title };
      }
      throw err;
    }
  }

  async getMessages(threadId: string, opts?: { limit?: number }): Promise<MastraMemoryMessage[]> {
    const conv = await this.client.shortTerm.getConversation(threadId, { limit: opts?.limit });
    return conv.messages.map((m) => ({
      id: m.id,
      threadId,
      role: m.role,
      content: m.content,
      createdAt: m.timestamp,
    }));
  }

  async saveMessage(input: {
    threadId: string;
    role: "user" | "assistant" | "system";
    content: string;
    metadata?: Record<string, unknown>;
  }): Promise<MastraMemoryMessage> {
    const m = await this.client.shortTerm.addMessage(input.threadId, input.role, input.content, {
      metadata: input.metadata,
    });
    return {
      id: m.id,
      threadId: input.threadId,
      role: m.role,
      content: m.content,
      createdAt: m.timestamp,
    };
  }

  async deleteThread(threadId: string): Promise<void> {
    try {
      await this.client.shortTerm.deleteConversation(threadId);
    } catch (err) {
      if (err instanceof NotSupportedError) {
        await this.client.shortTerm.clearSession(threadId);
        return;
      }
      throw err;
    }
  }
}
