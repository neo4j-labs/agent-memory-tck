/**
 * @neo4j-labs/agent-memory — TypeScript client for neo4j-agent-memory.
 *
 * @example
 * ```ts
 * import { MemoryClient } from "@neo4j-labs/agent-memory";
 *
 * const client = new MemoryClient({
 *   endpoint: "https://nams.neo4jsandbox.com",
 *   apiKey: "your-api-key",
 * });
 * await client.connect();
 *
 * // Short-term memory
 * const msg = await client.shortTerm.addMessage("session-1", "user", "Hello!");
 *
 * // Long-term memory
 * const entity = await client.longTerm.addEntity("Alice", "PERSON");
 *
 * // Reasoning memory
 * const trace = await client.reasoning.startTrace("session-1", "Lookup task");
 *
 * await client.close();
 * ```
 */

export { MemoryClient } from "./client.js";

// Types
export type {
  // Core types
  Message,
  Conversation,
  SessionInfo,
  Entity,
  EntityType,
  Preference,
  Fact,
  Relationship,
  ReasoningTrace,
  ReasoningStep,
  ToolCall,
  ToolStats,
  MessageRole,
  ToolCallStatus,
  // Options
  MemoryClientOptions,
  AddMessageOptions,
  GetConversationOptions,
  SearchMessagesOptions,
  ListSessionsOptions,
  SearchEntitiesOptions,
  SearchPreferencesOptions,
  GetRelatedEntitiesOptions,
  ListTracesOptions,
  RecordToolCallOptions,
  CompleteTraceOptions,
  AddRelationshipOptions,
  GetSimilarTracesOptions,
} from "./types.js";

// Transport
export type { Transport } from "./transport/index.js";
export { HttpTransport } from "./transport/http.js";

// Sub-clients (for advanced use)
export { ShortTermMemory } from "./short-term/index.js";
export { LongTermMemory } from "./long-term/index.js";
export { ReasoningMemory } from "./reasoning/index.js";

// Errors
export {
  MemoryError,
  ConnectionError,
  AuthenticationError,
  NotFoundError,
  ValidationError,
  TransportError,
} from "./errors.js";
