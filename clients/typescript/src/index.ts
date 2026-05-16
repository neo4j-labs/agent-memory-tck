/**
 * @neo4j-labs/agent-memory — TypeScript client for neo4j-agent-memory.
 *
 * Two transports ship in-box:
 *   - BridgeTransport: TCK bridge protocol, snake_case JSON.
 *   - RestTransport: the hosted REST API at https://memory.neo4jlabs.com/v1.
 *
 * Usage against the hosted service:
 *
 *     import { MemoryClient } from "@neo4j-labs/agent-memory";
 *
 *     const client = new MemoryClient({
 *       endpoint: "https://memory.neo4jlabs.com/v1",
 *       apiKey: process.env.MEMORY_API_KEY!,
 *     });
 *
 *     const conv = await client.shortTerm.createConversation({ userId: "alice" });
 *     await client.shortTerm.addMessage(conv.id, "user", "Hello!");
 *     const ctx = await client.shortTerm.getContext(conv.id);
 */

export { MemoryClient } from "./client.js";

// Types — short-term
export type {
  Message,
  Conversation,
  SessionInfo,
  ConversationContext,
  Observation,
  Reflection,
  MessageRole,
} from "./types.js";

// Types — long-term
export type {
  Entity,
  EntityType,
  HostedEntityType,
  EntityRelationshipRef,
  EntityHistory,
  EntityMention,
  EntityGraph,
  EntityGraphNode,
  EntityGraphEdge,
  EntityFeedbackResult,
  EntityMergeResult,
  Preference,
  Fact,
  Relationship,
} from "./types.js";

// Types — reasoning
export type {
  ReasoningTrace,
  ReasoningStep,
  ToolCall,
  ToolCallStatus,
  ToolStats,
  AgentStep,
  AgentStepExplanation,
  ConversationTrace,
  EntityProvenance,
} from "./types.js";

// Types — query / auth
export type { CypherResult, ApiKey, AccessTokenPair } from "./types.js";

// Options
export type {
  MemoryClientOptions,
  TransportMode,
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
  CreateConversationOptions,
  ListConversationsOptions,
  BulkMessageInput,
  ListEntitiesOptions,
  UpdateEntityOptions,
  SetEntityFeedbackOptions,
  RecordStepInput,
  CreateApiKeyInput,
} from "./types.js";

// Transports
export type { Transport } from "./transport/index.js";
export { BridgeTransport, RestTransport, HttpTransport } from "./transport/index.js";
export type {
  BridgeTransportOptions,
  RestTransportOptions,
  TokenProvider,
  HttpTransportOptions,
} from "./transport/index.js";

// Sub-clients (advanced)
export { ShortTermMemory } from "./short-term/index.js";
export { LongTermMemory } from "./long-term/index.js";
export { ReasoningMemory } from "./reasoning/index.js";
export { QueryConsole } from "./query/index.js";
export { AuthClient } from "./auth/index.js";

// Errors
export {
  MemoryError,
  ConnectionError,
  AuthenticationError,
  NotFoundError,
  NotSupportedError,
  ValidationError,
  TransportError,
} from "./errors.js";
