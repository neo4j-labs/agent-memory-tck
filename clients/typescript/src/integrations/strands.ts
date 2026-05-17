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
 * Strands snapshot state is persisted as synthetic `role: "system"`
 * messages on the NAMS conversation. NAMS exposes no conversation-
 * metadata-update endpoint, so we use the only write surface that does
 * exist: per-message metadata on `POST /conversations/{id}/messages`.
 *
 * Each save writes ONE synthetic message. Its `content` carries a
 * recognizable prefix (`__strands_state__:` for snapshots,
 * `__strands_manifest__:` for manifests) so callers walking the message
 * list can filter them out cheaply. The full JSON-serialized state rides
 * as a single string under `metadata.strands_state` on the message, where
 * the REST transport's snake_case ↔ camelCase casing layer can't reach
 * inside (string values are opaque to it).
 *
 * Per-snapshot synthetic messages mean `listSnapshotIds` is O(n) over the
 * message list, but in practice snapshots are small JSON deltas and the
 * conversation's message count is bounded — fine for v0.x preview.
 */
const STATE_PREFIX = "__strands_state__:";
const MANIFEST_PREFIX = "__strands_manifest__:";

/** Per-message metadata field that carries the JSON-stringified blob. */
const META_FIELD = "strands_state";

interface StrandsStateBlob {
  /** snapshotId associated with this synthetic message. */
  snapshotId: string;
  /** Whether this save asserted itself as the latest. */
  isLatest: boolean;
  /** Snapshot data with messages stripped. */
  snapshot: Snapshot;
  /** Wall-clock save time (ISO 8601). Tie-breaker for "latest" elections. */
  savedAt: string;
}

interface StrandsManifestBlob {
  /** SnapshotLocation.scopeId so multiple agents per session can co-exist. */
  scopeId: string;
  manifest: SnapshotManifest;
  savedAt: string;
}

/**
 * Returns true if a message is one of our synthetic state/manifest
 * markers. Exported so consumers walking the conversation can filter
 * them out of UI rendering. See `SYNTHETIC_MESSAGE_PREFIXES` for the
 * canonical prefix list.
 */
export function isSyntheticStrandsMessage(
  message: { role: string; content: string },
): boolean {
  if (message.role !== "system") return false;
  return (
    message.content.startsWith(STATE_PREFIX) ||
    message.content.startsWith(MANIFEST_PREFIX)
  );
}

/**
 * Canonical content prefixes used for synthetic messages. Consumers
 * (chat UIs, message-list renderers, Cypher queries) can filter on
 * these to skip the Strands-internal state messages.
 */
export const SYNTHETIC_MESSAGE_PREFIXES = [STATE_PREFIX, MANIFEST_PREFIX] as const;

/**
 * Implements Strands' `SnapshotStorage` against a NAMS `MemoryClient`.
 *
 * One Strands session = one NAMS Conversation (keyed by `location.sessionId`).
 * Snapshots are versions within that conversation:
 *
 * - Real conversation messages from `snapshot.data.messages` land as real
 *   `Message` graph nodes via `addMessage` (so entity extraction, search,
 *   and the graph view all work on them).
 * - Non-message snapshot state (Strands' `data` minus `messages`, plus
 *   `appData`, plus the manifest) is persisted as synthetic
 *   `role: "system"` messages whose content carries a recognizable
 *   marker prefix and whose per-message `metadata.strands_state` carries
 *   the JSON-serialized blob. NAMS exposes per-message metadata writes
 *   via `POST /conversations/{id}/messages`, so this works against the
 *   documented API today (no conversation-metadata-update endpoint).
 *
 * Consumers walking the message list (chat UIs, Cypher queries) should
 * filter synthetic messages with {@link isSyntheticStrandsMessage}.
 *
 * Auth errors propagate — Strands needs to know if the backing store is
 * unreachable. Transient errors propagate too; Strands' own retry
 * semantics (in `SessionManager`) apply.
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

    // 1. Extract conversation messages out of snapshot.data.messages and
    //    persist any new ones as real Message nodes. Dedupe by role+content
    //    so re-saving the same snapshot doesn't grow the message list.
    await this.extractAndPersistMessages(conversationId, snapshot);

    // 2. Write a synthetic system message that carries the rest of the
    //    snapshot's state (data minus messages, plus appData). The
    //    JSON blob rides as an opaque string in the message's per-message
    //    metadata so the REST transport's casing layer leaves it alone.
    const blob: StrandsStateBlob = {
      snapshotId,
      isLatest,
      snapshot: stripMessagesFromSnapshot(snapshot),
      savedAt: new Date().toISOString(),
    };
    await this.memory.shortTerm.addMessage(
      conversationId,
      "system",
      `${STATE_PREFIX}${snapshotId}`,
      { metadata: { [META_FIELD]: JSON.stringify(blob) } },
    );
  }

  async loadSnapshot(params: {
    location: SnapshotLocation;
    snapshotId?: string;
  }): Promise<Snapshot | null> {
    const conversationId = params.location.sessionId;
    const conv = await this.memory.shortTerm.getConversation(conversationId);

    const stateBlobs = this.readStateBlobs(conv.messages);
    if (stateBlobs.length === 0) return null;

    // If a snapshotId was requested, find that specific save.
    // Otherwise fall back to the most recent save where isLatest=true,
    // or the latest save overall if none asserted "latest".
    let blob: StrandsStateBlob | undefined;
    if (params.snapshotId) {
      blob = stateBlobs.find((b) => b.snapshotId === params.snapshotId);
    } else {
      blob = [...stateBlobs].reverse().find((b) => b.isLatest) ??
        stateBlobs[stateBlobs.length - 1];
    }
    if (!blob) return null;

    // Re-hydrate the snapshot: combine the stored data/appData with the
    // current conversation messages (filtered to drop our synthetic
    // markers so Strands doesn't replay them).
    const realMessages = conv.messages
      .filter((m) => !isSyntheticStrandsMessage(m))
      .map(toStrandsMessage);
    return mergeMessagesIntoSnapshot(blob.snapshot, realMessages);
  }

  async listSnapshotIds(params: {
    location: SnapshotLocation;
    limit?: number;
    startAfter?: string;
  }): Promise<string[]> {
    const conv = await this.memory.shortTerm.getConversation(params.location.sessionId);
    const stateBlobs = this.readStateBlobs(conv.messages);
    // Preserve save order. Dedupe per snapshotId in case a snapshotId is
    // saved more than once (Strands' contract permits re-saves).
    const seen = new Set<string>();
    const ids: string[] = [];
    for (const blob of stateBlobs) {
      if (seen.has(blob.snapshotId)) continue;
      seen.add(blob.snapshotId);
      ids.push(blob.snapshotId);
    }
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
    const conv = await this.memory.shortTerm.getConversation(params.location.sessionId);
    const blobs = this.readManifestBlobs(conv.messages);
    // Last write wins per scopeId — Strands writes manifests rarely so the
    // O(n) scan is fine.
    const matching = blobs.filter((b) => b.scopeId === params.location.scopeId);
    return matching[matching.length - 1]?.manifest ?? defaultManifest();
  }

  async saveManifest(params: {
    location: SnapshotLocation;
    manifest: SnapshotManifest;
  }): Promise<void> {
    const blob: StrandsManifestBlob = {
      scopeId: params.location.scopeId,
      manifest: params.manifest,
      savedAt: new Date().toISOString(),
    };
    await this.memory.shortTerm.addMessage(
      params.location.sessionId,
      "system",
      `${MANIFEST_PREFIX}${params.location.scopeId}`,
      { metadata: { [META_FIELD]: JSON.stringify(blob) } },
    );
  }

  // --- Internals ------------------------------------------------------------

  /**
   * Scan a conversation's message list and parse any state markers into
   * blobs, in original order.
   */
  private readStateBlobs(
    messages: Array<{ role: string; content: string; metadata?: Record<string, unknown> }>,
  ): StrandsStateBlob[] {
    const blobs: StrandsStateBlob[] = [];
    for (const msg of messages) {
      if (msg.role !== "system") continue;
      if (!msg.content.startsWith(STATE_PREFIX)) continue;
      const blob = parseBlob<StrandsStateBlob>(msg.metadata);
      if (blob) blobs.push(blob);
    }
    return blobs;
  }

  /** Same idea, for manifest markers. */
  private readManifestBlobs(
    messages: Array<{ role: string; content: string; metadata?: Record<string, unknown> }>,
  ): StrandsManifestBlob[] {
    const blobs: StrandsManifestBlob[] = [];
    for (const msg of messages) {
      if (msg.role !== "system") continue;
      if (!msg.content.startsWith(MANIFEST_PREFIX)) continue;
      const blob = parseBlob<StrandsManifestBlob>(msg.metadata);
      if (blob) blobs.push(blob);
    }
    return blobs;
  }

  /**
   * Pull the message list out of `snapshot.data.messages` (the canonical
   * Strands layout), find ones not yet present on the conversation
   * (excluding our synthetic markers), and persist them via `addMessage`.
   * Returns the number of new messages written.
   */
  private async extractAndPersistMessages(
    conversationId: string,
    snapshot: Snapshot,
  ): Promise<number> {
    const messages = pickStrandsMessages(snapshot);
    if (messages.length === 0) return 0;

    const existing = await this.memory.shortTerm.getConversation(conversationId);
    const seen = new Set(
      existing.messages
        .filter((m) => !isSyntheticStrandsMessage(m))
        .map((m) => `${m.role}::${m.content}`),
    );

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

function parseBlob<T>(metadata: Record<string, unknown> | undefined): T | null {
  if (!metadata) return null;
  const raw = metadata[META_FIELD];
  if (typeof raw !== "string") return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
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
