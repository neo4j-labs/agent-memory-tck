/**
 * Short-term (conversational) memory operations.
 *
 * Provides message storage, retrieval, search, and session management.
 * All methods correspond to Bronze-tier TCK requirements.
 */

import type { Transport } from "../transport/index.js";
import type {
  AddMessageOptions,
  Conversation,
  GetConversationOptions,
  ListSessionsOptions,
  Message,
  MessageRole,
  SearchMessagesOptions,
  SessionInfo,
} from "../types.js";

/** Wire format from the service (snake_case). */
interface WireMessage {
  id: string;
  role: string;
  content: string;
  timestamp: string;
  embedding?: number[];
  metadata?: Record<string, unknown>;
}

interface WireConversation {
  id: string;
  session_id: string;
  messages: WireMessage[];
  title?: string;
  created_at: string;
  updated_at?: string;
}

interface WireSessionInfo {
  session_id: string;
  message_count: number;
  created_at: string;
  updated_at?: string;
}

function toMessage(w: WireMessage): Message {
  return {
    id: w.id,
    role: w.role as MessageRole,
    content: w.content,
    timestamp: w.timestamp,
    embedding: w.embedding,
    metadata: w.metadata ?? {},
  };
}

function toConversation(w: WireConversation): Conversation {
  return {
    id: w.id,
    sessionId: w.session_id,
    messages: w.messages.map(toMessage),
    title: w.title,
    createdAt: w.created_at,
    updatedAt: w.updated_at,
  };
}

function toSessionInfo(w: WireSessionInfo): SessionInfo {
  return {
    sessionId: w.session_id,
    messageCount: w.message_count,
    createdAt: w.created_at,
    updatedAt: w.updated_at,
  };
}

export class ShortTermMemory {
  constructor(private readonly transport: Transport) {}

  async addMessage(
    sessionId: string,
    role: MessageRole,
    content: string,
    options?: AddMessageOptions,
  ): Promise<Message> {
    const wire = await this.transport.request<WireMessage>("add_message", {
      session_id: sessionId,
      role,
      content,
      metadata: options?.metadata,
    });
    return toMessage(wire);
  }

  async getConversation(
    sessionId: string,
    options?: GetConversationOptions,
  ): Promise<Conversation> {
    const wire = await this.transport.request<WireConversation>(
      "get_conversation",
      {
        session_id: sessionId,
        limit: options?.limit,
      },
    );
    return toConversation(wire);
  }

  async searchMessages(
    query: string,
    options?: SearchMessagesOptions,
  ): Promise<Message[]> {
    const wire = await this.transport.request<WireMessage[]>("search_messages", {
      query,
      session_id: options?.sessionId,
      limit: options?.limit ?? 10,
      threshold: options?.threshold ?? 0.7,
    });
    return wire.map(toMessage);
  }

  async listSessions(options?: ListSessionsOptions): Promise<SessionInfo[]> {
    const wire = await this.transport.request<WireSessionInfo[]>("list_sessions", {
      limit: options?.limit ?? 100,
    });
    return wire.map(toSessionInfo);
  }

  async deleteMessage(messageId: string): Promise<boolean> {
    const result = await this.transport.request<{ deleted: boolean }>(
      "delete_message",
      { message_id: messageId },
    );
    return result.deleted;
  }

  async clearSession(sessionId: string): Promise<void> {
    await this.transport.request("clear_session", { session_id: sessionId });
  }
}
