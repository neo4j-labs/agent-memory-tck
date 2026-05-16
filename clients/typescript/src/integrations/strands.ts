/**
 * Strands Agents SDK integration — three orthogonal surfaces, exposed
 * through a single subpath.
 *
 *   1. {@link Neo4jSessionStorage} — implements `SnapshotStorage` so
 *      Strands' `SessionManager` persists session state into a NAMS
 *      conversation. Hybrid mapping: messages from each snapshot land as
 *      real `Message` graph nodes via `addMessage`; the rest of the
 *      framework's per-snapshot state is stashed losslessly in the
 *      conversation's `metadata`.
 *
 *   2. {@link Neo4jConversationManager} — a `ConversationManager`
 *      subclass that delegates `reduce()` to an inner manager
 *      (defaults to `SlidingWindowConversationManager`) AND registers
 *      a `BeforeInvocationEvent` hook that prepends three-tier context
 *      (reflections + observations from `getContext()`) to every model
 *      call. Layered, not replacing — recent-history trimming still
 *      behaves the way the inner manager defines.
 *
 *   3. {@link registerReasoningHooks} — wires Strands hook events to
 *      our reasoning subclient. Each invocation opens a `ReasoningStep`;
 *      each tool call records against that step.
 *
 *   {@link connectMemoryToAgent} bundles all three for the common case.
 *
 * Strands lives in `devDependencies` only — every import below is a
 * type-only import, erased at compile time. The published
 * `dist/integrations/strands.js` has no runtime reference to
 * `@strands-agents/sdk`, so users without Strands installed pay zero
 * bundle cost.
 *
 * @example
 * ```ts
 * import { Agent } from "@strands-agents/sdk";
 * import { MemoryClient } from "@neo4j-labs/agent-memory";
 * import { connectMemoryToAgent } from "@neo4j-labs/agent-memory/integrations/strands";
 *
 * const memory = new MemoryClient();
 * const conv = await memory.shortTerm.createConversation({ userId: "alice" });
 *
 * const agent = new Agent({
 *   ...connectMemoryToAgent(memory, { conversationId: conv.id }),
 *   model,
 *   tools: [...],
 * });
 *
 * await agent.invoke("Tell me about graph databases.");
 * ```
 */

import type {
  BeforeInvocationEvent as BeforeInvocationEventType,
  AfterInvocationEvent as AfterInvocationEventType,
  BeforeToolCallEvent as BeforeToolCallEventType,
  AfterToolCallEvent as AfterToolCallEventType,
  LocalAgent,
  Message as StrandsMessage,
  Snapshot,
  SnapshotManifest,
  SnapshotStorage,
  SnapshotLocation,
  ConversationManager as StrandsConversationManager,
  ConversationManagerReduceOptions,
  SlidingWindowConversationManager as SlidingWindowConversationManagerType,
  SessionManager as SessionManagerType,
  SessionManagerConfig,
} from "@strands-agents/sdk";

import type { MemoryClient } from "../client.js";
import type { MessageRole } from "../types.js";

// ---------------------------------------------------------------------------
// Strands runtime imports are deferred to a small loader so callers who use
// only types still pay zero runtime cost. Callers who instantiate the
// classes below MUST have @strands-agents/sdk installed in their own
// dependencies — same contract as every other duck-typed integration.
// ---------------------------------------------------------------------------

type StrandsModule = typeof import("@strands-agents/sdk");

let _strandsModule: StrandsModule | null = null;

async function loadStrands(): Promise<StrandsModule> {
  if (!_strandsModule) {
    // Dynamic import keeps the static export graph free of @strands-agents/sdk.
    _strandsModule = (await import("@strands-agents/sdk")) as StrandsModule;
  }
  return _strandsModule;
}

// ---------------------------------------------------------------------------
// Public options
// ---------------------------------------------------------------------------

/** Options shared by every public entrypoint in this module. */
export interface StrandsIntegrationOptions {
  /**
   * NAMS Conversation id to wire to. Required by the convenience factory and
   * by individual exports that need correlation across invocations.
   */
  conversationId: string;

  /** Include reflections from `getContext()` in prompt injection. Default: true. */
  includeReflections?: boolean;

  /** Include observations from `getContext()` in prompt injection. Default: true. */
  includeObservations?: boolean;
}

/** Options for {@link Neo4jConversationManager}. */
export interface Neo4jConversationManagerOptions
  extends Pick<StrandsIntegrationOptions, "conversationId" | "includeReflections" | "includeObservations"> {
  /**
   * Inner `ConversationManager` to delegate `reduce()` to. When omitted,
   * defaults to `SlidingWindowConversationManager` (constructed lazily so
   * Strands' module is only loaded if the manager is actually used).
   */
  inner?: StrandsConversationManager;
}

/** Options for {@link registerReasoningHooks}. */
export interface ReasoningHooksOptions {
  /** NAMS Conversation id to attribute reasoning steps and tool calls to. */
  conversationId: string;
}

// ---------------------------------------------------------------------------
// SnapshotStorage
// ---------------------------------------------------------------------------

/**
 * All Strands round-trip state stashed in a NAMS conversation lives under
 * this single metadata field. We use one JSON-stringified blob to stay
 * opaque to the snake_case ↔ camelCase casing transform that the REST
 * transport applies to nested object keys.
 */
const META_FIELD = "strands_state";

interface StrandsMetadata {
  latestId?: string;
  history: string[];
  blobs: Record<string, Snapshot>;
  manifest?: SnapshotManifest;
}

function emptyStrandsMetadata(): StrandsMetadata {
  return { history: [], blobs: {} };
}

function parseStrandsMetadata(metadata: Record<string, unknown>): StrandsMetadata {
  const raw = metadata[META_FIELD];
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw) as Partial<StrandsMetadata>;
      return {
        latestId: parsed.latestId,
        history: Array.isArray(parsed.history) ? parsed.history : [],
        blobs: (parsed.blobs as Record<string, Snapshot>) ?? {},
        manifest: parsed.manifest,
      };
    } catch {
      return emptyStrandsMetadata();
    }
  }
  // Backwards-compat fallback: if the field is an object (some bridges may
  // pass through structured metadata without stringifying), accept it.
  if (raw && typeof raw === "object") {
    const obj = raw as Partial<StrandsMetadata>;
    return {
      latestId: obj.latestId,
      history: Array.isArray(obj.history) ? obj.history : [],
      blobs: (obj.blobs as Record<string, Snapshot>) ?? {},
      manifest: obj.manifest,
    };
  }
  return emptyStrandsMetadata();
}

/**
 * Implements Strands' `SnapshotStorage` against a NAMS `MemoryClient`.
 *
 * One Strands session = one NAMS Conversation (keyed by `location.sessionId`).
 * Snapshots are versions WITHIN that conversation: messages are persisted as
 * real `Message` nodes via `addMessage`, while non-message snapshot state is
 * round-tripped through `Conversation.metadata`.
 *
 * Auth errors propagate — Strands needs to know if the backing store is
 * unreachable. Transient errors propagate too; Strands' own retry semantics
 * (in `SessionManager`) apply.
 */
export class Neo4jSessionStorage implements SnapshotStorage {
  constructor(private readonly memory: MemoryClient) {}

  async saveSnapshot(params: {
    location: SnapshotLocation;
    snapshotId: string;
    isLatest: boolean;
    snapshot: Snapshot;
  }): Promise<void> {
    const { location, snapshotId, isLatest, snapshot } = params;
    const conversationId = location.sessionId;

    // 1. Extract the message list from snapshot.data and persist any new
    //    messages as Message nodes. We dedupe by content+role to avoid
    //    re-writing the same message on subsequent saves.
    await this.extractAndPersistMessages(conversationId, snapshot);

    // 2. Stash the non-message snapshot state into the strands_state metadata
    //    field, keyed by snapshotId. We keep ALL prior snapshot blobs so
    //    `listSnapshotIds` can enumerate them; in practice this stays small
    //    (snapshots are small JSON deltas).
    const state = await this.readState(conversationId);

    // Strip messages from the persisted blob — they live in the graph now.
    const blob = stripMessagesFromSnapshot(snapshot);
    state.blobs[snapshotId] = blob;
    if (!state.history.includes(snapshotId)) state.history.push(snapshotId);
    if (isLatest) state.latestId = snapshotId;

    await this.writeState(conversationId, state);
  }

  async loadSnapshot(params: {
    location: SnapshotLocation;
    snapshotId?: string;
  }): Promise<Snapshot | null> {
    const conversationId = params.location.sessionId;
    const state = await this.readState(conversationId);
    const wantedId = params.snapshotId ?? state.latestId;
    if (!wantedId) return null;

    const blob = state.blobs[wantedId];
    if (!blob) return null;

    // Re-hydrate the snapshot: combine the opaque blob with messages read
    // from the conversation graph.
    const conv = await this.memory.shortTerm.getConversation(conversationId);
    const messages = conv.messages.map(toStrandsMessage);
    return mergeMessagesIntoSnapshot(blob, messages);
  }

  async listSnapshotIds(params: {
    location: SnapshotLocation;
    limit?: number;
    startAfter?: string;
  }): Promise<string[]> {
    const state = await this.readState(params.location.sessionId);
    const ids = state.history.slice();
    let start = 0;
    if (params.startAfter) {
      const idx = ids.indexOf(params.startAfter);
      start = idx >= 0 ? idx + 1 : 0;
    }
    return ids.slice(start, params.limit ? start + params.limit : undefined);
  }

  async deleteSession(params: { sessionId: string }): Promise<void> {
    await this.memory.shortTerm.deleteConversation(params.sessionId);
  }

  async loadManifest(params: { location: SnapshotLocation }): Promise<SnapshotManifest> {
    const state = await this.readState(params.location.sessionId);
    return state.manifest ?? defaultManifest();
  }

  async saveManifest(params: {
    location: SnapshotLocation;
    manifest: SnapshotManifest;
  }): Promise<void> {
    const conversationId = params.location.sessionId;
    const state = await this.readState(conversationId);
    state.manifest = params.manifest;
    await this.writeState(conversationId, state);
  }

  // --- Internals ------------------------------------------------------------

  private async readState(conversationId: string): Promise<StrandsMetadata> {
    const conv = await this.memory.shortTerm.getConversationMetadata(conversationId);
    const metadata = (conv.metadata as Record<string, unknown> | undefined) ?? {};
    return parseStrandsMetadata(metadata);
  }

  private async writeState(
    conversationId: string,
    state: StrandsMetadata,
  ): Promise<void> {
    // Strands state lives under a single JSON-stringified key to stay
    // opaque to the REST transport's snake_case ↔ camelCase casing
    // transform, which otherwise would mangle nested keys.
    await (this.memory as unknown as {
      transport: { request<T>(method: string, params: Record<string, unknown>): Promise<T> };
    }).transport.request("update_conversation_metadata", {
      conversation_id: conversationId,
      metadata: {
        [META_FIELD]: JSON.stringify(state),
      },
    });
  }

  /**
   * Pull the message list out of `snapshot.data.messages` (the canonical
   * Strands layout), find ones not yet present on the conversation, and
   * persist them via `addMessage`. Returns the number of new messages
   * written.
   */
  private async extractAndPersistMessages(
    conversationId: string,
    snapshot: Snapshot,
  ): Promise<number> {
    const messages = pickStrandsMessages(snapshot);
    if (messages.length === 0) return 0;

    const existing = await this.memory.shortTerm.getConversation(conversationId);
    const seen = new Set(existing.messages.map((m) => `${m.role}::${m.content}`));

    let writes = 0;
    for (const msg of messages) {
      const text = strandsMessageToText(msg);
      const key = `${msg.role}::${text}`;
      if (seen.has(key)) continue;
      seen.add(key);
      await this.memory.shortTerm.addMessage(conversationId, msg.role as MessageRole, text);
      writes++;
    }
    return writes;
  }
}

// ---------------------------------------------------------------------------
// ConversationManager
// ---------------------------------------------------------------------------

/**
 * Layered ConversationManager: context-injection hook + inner manager.
 *
 * The inner manager (defaults to `SlidingWindowConversationManager`) owns
 * trimming and summarization. This manager registers a
 * `BeforeInvocationEvent` hook that prepends reflections + observations from
 * `getContext()` as system messages, BEFORE the inner manager's reduce
 * logic runs.
 *
 * Lazily constructs an inner manager on first `initAgent` invocation so
 * importing this module doesn't load Strands' runtime unless the manager
 * is actually used.
 */
export class Neo4jConversationManager {
  public readonly name = "neo4j:context-injection";
  /**
   * Mirrored from Strands' `ConversationManager` to satisfy duck-typing
   * at compile time. We never set it — context injection has no notion
   * of a compression threshold.
   */
  protected readonly _compressionThreshold: number | undefined = undefined;

  // We can't extend Strands' abstract class via a static `extends` clause
  // because Strands is a dynamic import — the base class identity isn't
  // known at module-load time. Instead we *delegate* to a lazily-built
  // inner manager and implement the abstract surface explicitly. Strands
  // duck-types on shape, not on instanceof, so this works.

  private inner: StrandsConversationManager | null = null;

  constructor(
    private readonly memory: MemoryClient,
    private readonly options: Neo4jConversationManagerOptions,
  ) {}

  async reduce(opts: ConversationManagerReduceOptions): Promise<boolean> {
    const inner = await this.ensureInner();
    return inner.reduce(opts);
  }

  async initAgent(agent: LocalAgent): Promise<void> {
    const inner = await this.ensureInner();
    inner.initAgent(agent);

    const strands = await loadStrands();
    // Register a hook to inject three-tier context BEFORE every model call.
    agent.addHook(
      strands.BeforeInvocationEvent,
      async (event: BeforeInvocationEventType) => {
        await this.injectContext(event);
      },
    );
  }

  private async ensureInner(): Promise<StrandsConversationManager> {
    if (this.inner) return this.inner;
    if (this.options.inner) {
      this.inner = this.options.inner;
      return this.inner;
    }
    const strands = await loadStrands();
    const Ctor =
      strands.SlidingWindowConversationManager as new () => SlidingWindowConversationManagerType;
    this.inner = new Ctor();
    return this.inner;
  }

  private async injectContext(event: BeforeInvocationEventType): Promise<void> {
    try {
      const ctx = await this.memory.shortTerm.getContext(this.options.conversationId);
      const prepend: StrandsMessage[] = [];
      const includeReflections = this.options.includeReflections ?? true;
      const includeObservations = this.options.includeObservations ?? true;

      if (includeReflections && ctx.reflections.length > 0) {
        for (const r of ctx.reflections) {
          prepend.push(systemTextMessage(`[reflection] ${r.content}`));
        }
      }
      if (includeObservations && ctx.observations.length > 0) {
        for (const o of ctx.observations) {
          prepend.push(systemTextMessage(`[observation] ${o.content}`));
        }
      }

      if (prepend.length === 0) return;

      // Prepend by mutating agent.messages in place. The order MUST be
      // [context...] + [existing messages...]. Strands' inner manager
      // may later trim from the head — that's intentional (these
      // injections aren't sacred; staleness > overflow).
      const agentLike = event.agent as unknown as { messages: StrandsMessage[] };
      agentLike.messages = [...prepend, ...agentLike.messages];
    } catch {
      // Context injection is best-effort. A failed getContext() (transient,
      // not-supported, etc.) must not break the agent run — we just fall
      // back to whatever the inner manager produces.
    }
  }
}

// ---------------------------------------------------------------------------
// Reasoning hooks
// ---------------------------------------------------------------------------

/** Key in `invocationState` where the current reasoning step id is stashed. */
const INVOCATION_STEP_ID_KEY = "__neo4jReasoningStepId";
/** Key in `invocationState` where the per-invocation tool-call → toolCallId map lives. */
const TOOL_CALL_MAP_KEY = "__neo4jReasoningToolCalls";

/**
 * Wire reasoning capture onto a Strands `HookRegistry`.
 *
 * - `BeforeInvocationEvent` → `reasoning.recordStep` (opens a step; stashes
 *   step id on `event.invocationState`).
 * - `AfterInvocationEvent` → re-records the step with a `result` field
 *   (best-effort; we don't have a public `updateStep` API yet, so the
 *   second write supplements rather than mutates).
 * - `BeforeToolCallEvent` → `reasoning.recordToolCall` with status
 *   `pending`. Strands tool-call id → our tool-call id map stashed on
 *   `invocationState`.
 * - `AfterToolCallEvent` → updates the recorded tool call's status.
 *
 * All capture is best-effort: every reasoning write is wrapped in try/catch
 * and silently swallowed on failure. Reasoning capture must never break the
 * agent run.
 */
export async function registerReasoningHooks(
  memory: MemoryClient,
  agent: LocalAgent,
  options: ReasoningHooksOptions,
): Promise<void> {
  return registerReasoningHooksOnAgent(memory, agent, options);
}

async function registerReasoningHooksOnAgent(
  memory: MemoryClient,
  agent: LocalAgent,
  options: ReasoningHooksOptions,
): Promise<void> {
  const strands = await loadStrands();
  const conversationId = options.conversationId;

  agent.addHook(strands.BeforeInvocationEvent, async (event: BeforeInvocationEventType) => {
    try {
      const step = await memory.reasoning.recordStep({
        conversationId,
        reasoning: "agent invocation started",
        actionTaken: "invoke_agent",
      });
      (event.invocationState as Record<string, unknown>)[INVOCATION_STEP_ID_KEY] = step.id;
      (event.invocationState as Record<string, unknown>)[TOOL_CALL_MAP_KEY] = new Map<
        string,
        string
      >();
    } catch {
      /* best-effort */
    }
  });

  agent.addHook(strands.AfterInvocationEvent, async (event: AfterInvocationEventType) => {
    try {
      const stepId = (event.invocationState as Record<string, unknown>)[INVOCATION_STEP_ID_KEY];
      if (typeof stepId !== "string") return;
      // Record a follow-up step with the result, since the current
      // reasoning API doesn't expose updateStep. This is intentional —
      // the after-invocation marker is a separate point in the trace.
      await memory.reasoning.recordStep({
        conversationId,
        reasoning: `agent invocation completed (step ${stepId})`,
        actionTaken: "invocation_complete",
        result: "ok",
      });
    } catch {
      /* best-effort */
    }
  });

  agent.addHook(strands.BeforeToolCallEvent, async (event: BeforeToolCallEventType) => {
    try {
      const stepId = (event.invocationState as Record<string, unknown>)[INVOCATION_STEP_ID_KEY];
      if (typeof stepId !== "string") return;
      const toolCall = await memory.reasoning.recordToolCall(
        stepId,
        event.toolUse.name,
        event.toolUse.input as Record<string, unknown>,
        { status: "pending" },
      );
      const map = (event.invocationState as Record<string, unknown>)[TOOL_CALL_MAP_KEY];
      if (map instanceof Map) {
        map.set(event.toolUse.toolUseId, toolCall.id);
      }
    } catch {
      /* best-effort */
    }
  });

  agent.addHook(strands.AfterToolCallEvent, async (event: AfterToolCallEventType) => {
    try {
      const stepId = (event.invocationState as Record<string, unknown>)[INVOCATION_STEP_ID_KEY];
      if (typeof stepId !== "string") return;
      // We don't have a public updateToolCall API yet either — record a
      // follow-up tool-call entry with the resolved status. Pair-up via
      // the same toolUseId-keyed map for future updateToolCall support.
      await memory.reasoning.recordToolCall(
        stepId,
        event.toolUse.name,
        event.toolUse.input as Record<string, unknown>,
        {
          status: event.error ? "failure" : "success",
          error: event.error?.message,
        },
      );
    } catch {
      /* best-effort */
    }
  });
}

// ---------------------------------------------------------------------------
// Convenience factory
// ---------------------------------------------------------------------------

/** Result of {@link connectMemoryToAgent} — spread directly into `new Agent({ ... })`. */
export interface ConnectMemoryToAgentResult {
  sessionManager: SessionManagerType;
  /**
   * Typed as `StrandsConversationManager` (the abstract base) so callers
   * can spread the result straight into `new Agent({ ... })` without
   * casts. At runtime this is a {@link Neo4jConversationManager}.
   */
  conversationManager: StrandsConversationManager;
}

/**
 * One-shot helper that wires the SessionStorage, the ConversationManager, and
 * (lazily) the reasoning hooks against a NAMS `MemoryClient`. Spread the
 * return value into `new Agent({ ... })`.
 *
 * Reasoning hooks attach themselves automatically when the conversation
 * manager's `initAgent` runs — no separate registration step required.
 */
export async function connectMemoryToAgent(
  memory: MemoryClient,
  options: StrandsIntegrationOptions,
): Promise<ConnectMemoryToAgentResult> {
  const strands = await loadStrands();
  const sessionManager = new strands.SessionManager({
    sessionId: options.conversationId,
    storage: { snapshot: new Neo4jSessionStorage(memory) },
  } satisfies SessionManagerConfig);

  // Wrap Neo4jConversationManager so its initAgent ALSO registers the
  // reasoning hooks. Cleaner than asking the caller to do two things.
  const baseManager = new Neo4jConversationManager(memory, options);
  const originalInit = baseManager.initAgent.bind(baseManager);
  baseManager.initAgent = async (agent: LocalAgent) => {
    await originalInit(agent);
    await registerReasoningHooksOnAgent(memory, agent, {
      conversationId: options.conversationId,
    });
  };

  return {
    sessionManager,
    conversationManager: baseManager as unknown as StrandsConversationManager,
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function defaultManifest(): SnapshotManifest {
  return {
    schemaVersion: "1.0",
    updatedAt: new Date().toISOString(),
  };
}

function pickStrandsMessages(snapshot: Snapshot): StrandsMessage[] {
  const data = snapshot.data as { messages?: unknown } | undefined;
  if (!data || !Array.isArray(data.messages)) return [];
  return data.messages as StrandsMessage[];
}

function stripMessagesFromSnapshot(snapshot: Snapshot): Snapshot {
  // Defensive shallow copy; messages live in the graph from here on.
  const nextData = { ...(snapshot.data ?? {}) };
  delete (nextData as Record<string, unknown>).messages;
  return { ...snapshot, data: nextData };
}

function mergeMessagesIntoSnapshot(
  blob: Snapshot,
  messages: StrandsMessage[],
): Snapshot {
  // Cast through unknown — Snapshot.data is typed as Record<string, JSONValue>
  // but Strands itself stores messages there, so the runtime shape matches.
  return {
    ...blob,
    data: { ...(blob.data ?? {}), messages: messages as unknown as never },
  };
}

function strandsMessageToText(msg: StrandsMessage): string {
  // Strands messages carry ContentBlock[]. Flatten plain-text blocks into a
  // single string; non-text blocks (images, tool uses) are described by tag.
  const blocks = (msg as unknown as { content: unknown[] }).content ?? [];
  if (!Array.isArray(blocks)) return "";
  const parts: string[] = [];
  for (const b of blocks) {
    if (b && typeof b === "object") {
      const block = b as { text?: unknown; type?: string };
      if (typeof block.text === "string") {
        parts.push(block.text);
      } else if (block.type) {
        parts.push(`[${block.type}]`);
      }
    }
  }
  return parts.join("\n");
}

function toStrandsMessage(m: { role: string; content: string }): StrandsMessage {
  return {
    role: m.role as StrandsMessage["role"],
    content: [{ text: m.content }] as unknown as StrandsMessage["content"],
  } as StrandsMessage;
}

function systemTextMessage(text: string): StrandsMessage {
  return {
    role: "user" as StrandsMessage["role"],
    content: [{ text }] as unknown as StrandsMessage["content"],
  } as StrandsMessage;
}
