/**
 * Short-term (conversational) memory operations.
 *
 * Bridge methods (Bronze tier) speak the TCK BaseAdapter contract.
 * Hosted methods speak the Volume 5 / Platinum tier hosted service operations.
 */

import type { Transport } from "../transport/index.js";
import type {
  AddMessageOptions,
  BulkMessageInput,
  Conversation,
  ConversationContext,
  CreateConversationOptions,
  GetConversationOptions,
  ListConversationsOptions,
  ListSessionsOptions,
  Message,
  MessageRole,
  Observation,
  Reflection,
  SearchMessagesOptions,
  SessionInfo,
} from "../types.js";

/** Wire format from the bridge protocol (snake_case). */
interface WireMessage {
  id: string;
  role: string;
  content: string;
  timestamp?: string;
  created_at?: string;
  embedding?: number[];
  metadata?: Record<string, unknown>;
  conversation_id?: string;
}

interface WireConversation {
  id: string;
  session_id?: string;
  messages?: WireMessage[];
  message_count?: number;
  title?: string;
  created_at?: string;
  updated_at?: string;
  workspace_id?: string;
  user_id?: string;
  metadata?: Record<string, unknown>;
}

interface WireSessionInfo {
  session_id?: string;
  id?: string;
  message_count: number;
  created_at: string;
  updated_at?: string;
  user_id?: string;
}

interface WireObservation {
  id: string;
  conversation_id: string;
  content: string;
  window_start?: string;
  window_end?: string;
  created_at: string;
}

interface WireReflection {
  id: string;
  conversation_id: string;
  content: string;
  created_at: string;
}

interface WireContext {
  reflections?: WireReflection[];
  observations?: WireObservation[];
  recent_messages?: WireMessage[];
}

function toMessage(w: WireMessage): Message {
  return {
    id: w.id,
    role: (w.role ?? "user") as MessageRole,
    content: w.content,
    timestamp: w.timestamp ?? w.created_at ?? "",
    embedding: w.embedding,
    metadata: w.metadata ?? {},
    conversationId: w.conversation_id,
  };
}

function toConversation(w: WireConversation): Conversation {
  return {
    id: w.id,
    sessionId: w.session_id ?? w.id,
    messages: (w.messages ?? []).map(toMessage),
    messageCount: w.message_count,
    title: w.title,
    createdAt: w.created_at ?? "",
    updatedAt: w.updated_at,
    workspaceId: w.workspace_id,
    userId: w.user_id,
    metadata: w.metadata,
  };
}

function toSessionInfo(w: WireSessionInfo): SessionInfo {
  return {
    sessionId: w.session_id ?? w.id ?? "",
    messageCount: w.message_count ?? 0,
    createdAt: w.created_at,
    updatedAt: w.updated_at,
  };
}

function toObservation(w: WireObservation): Observation {
  return {
    id: w.id,
    conversationId: w.conversation_id,
    content: w.content,
    windowStart: w.window_start,
    windowEnd: w.window_end,
    createdAt: w.created_at,
  };
}

function toReflection(w: WireReflection): Reflection {
  return {
    id: w.id,
    conversationId: w.conversation_id,
    content: w.content,
    createdAt: w.created_at,
  };
}

export class ShortTermMemory {
  constructor(private readonly transport: Transport) {}

  // ---- Bronze tier (bridge) ----------------------------------------------

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
    const wire = await this.transport.request<WireConversation>("get_conversation", {
      session_id: sessionId,
      limit: options?.limit,
    });
    return toConversation(wire);
  }

  async searchMessages(query: string, options?: SearchMessagesOptions): Promise<Message[]> {
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
    const result = await this.transport.request<{ deleted: boolean }>("delete_message", {
      message_id: messageId,
    });
    return result.deleted;
  }

  async clearSession(sessionId: string): Promise<void> {
    await this.transport.request("clear_session", { session_id: sessionId });
  }

  // ---- Volume 5 / hosted-native methods -----------------------------------

  /** Create a new conversation (hosted service). */
  async createConversation(options: CreateConversationOptions): Promise<Conversation> {
    const wire = await this.transport.request<WireConversation>("create_conversation", {
      user_id: options.userId,
      metadata: options.metadata,
    });
    return toConversation(wire);
  }

  /** List conversations the API key has access to. */
  async listConversations(options?: ListConversationsOptions): Promise<Conversation[]> {
    const wire = await this.transport.request<WireConversation[]>("list_conversations", {
      limit: options?.limit,
      userId: options?.userId,
    });
    return wire.map(toConversation);
  }

  /** Fetch conversation metadata (no messages). */
  async getConversationMetadata(conversationId: string): Promise<Conversation> {
    const wire = await this.transport.request<WireConversation>("get_conversation_metadata", {
      conversation_id: conversationId,
    });
    return toConversation(wire);
  }

  /** Delete a conversation and all its messages. */
  async deleteConversation(conversationId: string): Promise<void> {
    await this.transport.request("delete_conversation", { conversation_id: conversationId });
  }

  /**
   * Three-tier conversational context (reflections + observations + recent
   * messages). The richest input you can hand an LLM about a conversation.
   */
  async getContext(conversationId: string): Promise<ConversationContext> {
    const wire = await this.transport.request<WireContext>("get_context", {
      conversation_id: conversationId,
    });
    return {
      reflections: (wire.reflections ?? []).map(toReflection),
      observations: (wire.observations ?? []).map(toObservation),
      recentMessages: (wire.recent_messages ?? []).map(toMessage),
    };
  }

  /** Bulk-add up to 100 messages in one request. */
  async bulkAddMessages(
    conversationId: string,
    messages: BulkMessageInput[],
  ): Promise<Message[]> {
    if (messages.length > 100) {
      throw new Error("bulkAddMessages accepts a maximum of 100 messages per call.");
    }
    const wire = await this.transport.request<WireMessage[]>("bulk_add_messages", {
      conversation_id: conversationId,
      messages,
    });
    return wire.map(toMessage);
  }

  /** Auto-generated message-window summaries. */
  async getObservations(
    conversationId: string,
    options?: { limit?: number },
  ): Promise<Observation[]> {
    const wire = await this.transport.request<WireObservation[]>("get_observations", {
      conversation_id: conversationId,
      limit: options?.limit,
    });
    return wire.map(toObservation);
  }

  /** Higher-level reflections derived from observations. */
  async getReflections(conversationId: string): Promise<Reflection[]> {
    const wire = await this.transport.request<WireReflection[]>("get_reflections", {
      conversation_id: conversationId,
    });
    return wire.map(toReflection);
  }
}
