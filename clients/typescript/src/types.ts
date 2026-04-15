/**
 * Core type definitions for neo4j-agent-memory TypeScript client.
 *
 * These types mirror the TCK Pydantic models defined in
 * tck/adapters/base_adapter.py 1:1. All IDs are strings (UUID serialized),
 * all timestamps are ISO 8601 strings for edge runtime compatibility.
 */

// ---------------------------------------------------------------------------
// Enums (as string unions for maximum compatibility)
// ---------------------------------------------------------------------------

export type MessageRole = "user" | "assistant" | "system";

export type ToolCallStatus =
  | "pending"
  | "success"
  | "failure"
  | "error"
  | "timeout"
  | "cancelled";

// ---------------------------------------------------------------------------
// Short-Term Memory Types
// ---------------------------------------------------------------------------

export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  timestamp: string;
  embedding?: number[];
  metadata: Record<string, unknown>;
}

export interface Conversation {
  id: string;
  sessionId: string;
  messages: Message[];
  title?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface SessionInfo {
  sessionId: string;
  messageCount: number;
  createdAt: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Long-Term Memory Types
// ---------------------------------------------------------------------------

export type EntityType =
  | "PERSON"
  | "ORGANIZATION"
  | "LOCATION"
  | "EVENT"
  | "OBJECT";

export interface Entity {
  id: string;
  name: string;
  type: string;
  subtype?: string;
  description?: string;
  embedding?: number[];
  canonicalName?: string;
  createdAt: string;
}

export interface Preference {
  id: string;
  category: string;
  preference: string;
  context?: string;
  embedding?: number[];
}

export interface Fact {
  id: string;
  subject: string;
  predicate: string;
  object: string;
  embedding?: number[];
}

export interface Relationship {
  id: string;
  sourceId: string;
  targetId: string;
  relationshipType: string;
  properties: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Reasoning Memory Types
// ---------------------------------------------------------------------------

export interface ReasoningTrace {
  id: string;
  sessionId: string;
  task: string;
  steps: ReasoningStep[];
  outcome?: string;
  success?: boolean;
  startedAt: string;
  completedAt?: string;
}

export interface ReasoningStep {
  id: string;
  traceId: string;
  stepNumber: number;
  thought?: string;
  action?: string;
  observation?: string;
  toolCalls: ToolCall[];
}

export interface ToolCall {
  id: string;
  toolName: string;
  arguments: Record<string, unknown>;
  result?: unknown;
  status: ToolCallStatus;
  durationMs?: number;
  error?: string;
}

export interface ToolStats {
  name: string;
  totalCalls: number;
  successfulCalls: number;
  failedCalls: number;
  successRate: number;
  avgDurationMs?: number;
}

// ---------------------------------------------------------------------------
// Client Configuration
// ---------------------------------------------------------------------------

export interface MemoryClientOptions {
  /** URL of the NAMS or compatible HTTP endpoint. */
  endpoint?: string;

  /** API key for authentication with the hosted service. */
  apiKey?: string;

  /** Neo4j connection URI (for direct mode). Requires neo4j-driver peer dep. */
  neo4jUri?: string;

  /** Neo4j username (for direct mode). */
  neo4jUsername?: string;

  /** Neo4j password (for direct mode). */
  neo4jPassword?: string;

  /** Shared entity namespace for multi-agent collaboration. */
  namespace?: string;

  /** Request timeout in milliseconds. Default: 30000. */
  timeout?: number;
}

// ---------------------------------------------------------------------------
// Operation Options
// ---------------------------------------------------------------------------

export interface AddMessageOptions {
  metadata?: Record<string, unknown>;
}

export interface GetConversationOptions {
  limit?: number;
}

export interface SearchMessagesOptions {
  sessionId?: string;
  limit?: number;
  threshold?: number;
}

export interface ListSessionsOptions {
  limit?: number;
}

export interface SearchEntitiesOptions {
  limit?: number;
}

export interface SearchPreferencesOptions {
  category?: string;
  limit?: number;
}

export interface GetRelatedEntitiesOptions {
  relationshipType?: string;
  depth?: number;
}

export interface ListTracesOptions {
  sessionId?: string;
  limit?: number;
}

export interface RecordToolCallOptions {
  result?: unknown;
  status?: ToolCallStatus;
  durationMs?: number;
  error?: string;
}

export interface CompleteTraceOptions {
  outcome?: string;
  success?: boolean;
}

export interface AddRelationshipOptions {
  properties?: Record<string, unknown>;
}

export interface GetSimilarTracesOptions {
  limit?: number;
  successOnly?: boolean;
}
