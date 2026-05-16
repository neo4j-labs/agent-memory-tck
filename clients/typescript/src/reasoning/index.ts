/**
 * Reasoning memory operations.
 *
 * Bridge methods (Silver tier) wrap traces. Volume 5 / hosted-native methods
 * are flatter — steps belong directly to a conversation, with explain &
 * provenance views for the reasoning trail behind any entity.
 */

import type { Transport } from "../transport/index.js";
import type {
  AgentStep,
  AgentStepExplanation,
  CompleteTraceOptions,
  ConversationTrace,
  Entity,
  EntityProvenance,
  GetSimilarTracesOptions,
  ListTracesOptions,
  ReasoningStep,
  ReasoningTrace,
  RecordStepInput,
  RecordToolCallOptions,
  ToolCall,
  ToolCallStatus,
  ToolStats,
} from "../types.js";

interface WireToolCall {
  id: string;
  tool_name?: string;
  toolName?: string;
  arguments?: Record<string, unknown>;
  input?: string;
  result?: unknown;
  output?: unknown;
  status: string;
  duration_ms?: number;
  durationMs?: number;
  error?: string;
  step_id?: string;
}

interface WireStep {
  id: string;
  trace_id?: string;
  step_number?: number;
  thought?: string;
  action?: string;
  observation?: string;
  tool_calls?: WireToolCall[];
}

interface WireTrace {
  id: string;
  session_id?: string;
  task?: string;
  steps?: WireStep[];
  outcome?: string;
  success?: boolean;
  started_at?: string;
  completed_at?: string;
}

interface WireToolStats {
  name: string;
  total_calls: number;
  successful_calls: number;
  failed_calls: number;
  success_rate: number;
  avg_duration_ms?: number;
}

interface WireAgentStep {
  id: string;
  conversation_id: string;
  reasoning: string;
  action_taken: string;
  result?: string;
  created_at?: string;
}

interface WireAgentStepExplanation extends WireAgentStep {
  tool_calls?: WireToolCall[];
  influenced_entities?: Array<Record<string, unknown>>;
}

interface WireConversationTrace {
  conversation_id: string;
  steps?: WireAgentStep[];
  tool_calls?: WireToolCall[];
}

interface WireEntityProvenance {
  entity_id: string;
  steps?: WireAgentStep[];
}

function toToolCall(w: WireToolCall): ToolCall {
  return {
    id: w.id,
    toolName: w.tool_name ?? w.toolName ?? "",
    arguments:
      w.arguments ??
      (w.input ? safeParseObject(w.input) : {}) ??
      {},
    result: w.result ?? w.output,
    status: (w.status ?? "success") as ToolCallStatus,
    durationMs: w.duration_ms ?? w.durationMs,
    error: w.error,
  };
}

function safeParseObject(input: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(input);
    return typeof parsed === "object" && parsed !== null
      ? (parsed as Record<string, unknown>)
      : { value: parsed };
  } catch {
    return { raw: input };
  }
}

function toStep(w: WireStep): ReasoningStep {
  return {
    id: w.id,
    traceId: w.trace_id ?? "",
    stepNumber: w.step_number ?? 0,
    thought: w.thought,
    action: w.action,
    observation: w.observation,
    toolCalls: (w.tool_calls ?? []).map(toToolCall),
  };
}

function toTrace(w: WireTrace): ReasoningTrace {
  return {
    id: w.id,
    sessionId: w.session_id ?? "",
    task: w.task ?? "",
    steps: (w.steps ?? []).map(toStep),
    outcome: w.outcome,
    success: w.success,
    startedAt: w.started_at ?? "",
    completedAt: w.completed_at,
  };
}

function toToolStats(w: WireToolStats): ToolStats {
  return {
    name: w.name,
    totalCalls: w.total_calls,
    successfulCalls: w.successful_calls,
    failedCalls: w.failed_calls,
    successRate: w.success_rate,
    avgDurationMs: w.avg_duration_ms,
  };
}

function toAgentStep(w: WireAgentStep): AgentStep {
  return {
    id: w.id,
    conversationId: w.conversation_id,
    reasoning: w.reasoning,
    actionTaken: w.action_taken,
    result: w.result,
    createdAt: w.created_at ?? "",
  };
}

export class ReasoningMemory {
  constructor(private readonly transport: Transport) {}

  // ---- Silver tier (bridge) ----------------------------------------------

  async startTrace(sessionId: string, task: string): Promise<ReasoningTrace> {
    const wire = await this.transport.request<WireTrace>("start_trace", {
      session_id: sessionId,
      task,
    });
    return toTrace(wire);
  }

  async addStep(
    traceId: string,
    options?: { thought?: string; action?: string; observation?: string },
  ): Promise<ReasoningStep> {
    const wire = await this.transport.request<WireStep>("add_step", {
      trace_id: traceId,
      thought: options?.thought,
      action: options?.action,
      observation: options?.observation,
    });
    return toStep(wire);
  }

  async recordToolCall(
    stepId: string,
    toolName: string,
    args: Record<string, unknown>,
    options?: RecordToolCallOptions,
  ): Promise<ToolCall> {
    const wire = await this.transport.request<WireToolCall>("record_tool_call", {
      step_id: stepId,
      tool_name: toolName,
      arguments: args,
      input: typeof args === "string" ? args : JSON.stringify(args),
      result: options?.result,
      output: typeof options?.result === "string" ? options.result : undefined,
      status: options?.status ?? "success",
      duration_ms: options?.durationMs,
      error: options?.error,
    });
    return toToolCall(wire);
  }

  async completeTrace(
    traceId: string,
    options?: CompleteTraceOptions,
  ): Promise<ReasoningTrace> {
    const wire = await this.transport.request<WireTrace>("complete_trace", {
      trace_id: traceId,
      outcome: options?.outcome,
      success: options?.success,
    });
    return toTrace(wire);
  }

  async getTraceWithSteps(traceId: string): Promise<ReasoningTrace | null> {
    const wire = await this.transport.request<WireTrace | null>("get_trace_with_steps", {
      trace_id: traceId,
    });
    return wire ? toTrace(wire) : null;
  }

  async listTraces(options?: ListTracesOptions): Promise<ReasoningTrace[]> {
    const wire = await this.transport.request<WireTrace[]>("list_traces", {
      session_id: options?.sessionId,
      limit: options?.limit ?? 100,
    });
    return wire.map(toTrace);
  }

  async getToolStats(toolName?: string): Promise<ToolStats[]> {
    const wire = await this.transport.request<WireToolStats[]>("get_tool_stats", {
      tool_name: toolName,
    });
    return wire.map(toToolStats);
  }

  async getSimilarTraces(
    task: string,
    options?: GetSimilarTracesOptions,
  ): Promise<ReasoningTrace[]> {
    const wire = await this.transport.request<WireTrace[]>("get_similar_traces", {
      task,
      limit: options?.limit ?? 5,
      success_only: options?.successOnly ?? true,
    });
    return wire.map(toTrace);
  }

  // ---- Volume 5 / hosted-native methods -----------------------------------

  /** Record one reasoning step under a conversation (hosted REACT model). */
  async recordStep(input: RecordStepInput): Promise<AgentStep> {
    const wire = await this.transport.request<WireAgentStep>("record_step", {
      conversation_id: input.conversationId,
      reasoning: input.reasoning,
      action_taken: input.actionTaken,
      result: input.result,
    });
    return toAgentStep(wire);
  }

  /** List all reasoning steps for one conversation. */
  async listSteps(conversationId: string): Promise<AgentStep[]> {
    const wire = await this.transport.request<WireAgentStep[]>("list_steps", {
      conversation_id: conversationId,
    });
    return wire.map(toAgentStep);
  }

  /** Detailed step explanation with tool calls and influenced entities. */
  async explainStep(stepId: string): Promise<AgentStepExplanation> {
    const wire = await this.transport.request<WireAgentStepExplanation>("explain_step", {
      step_id: stepId,
    });
    return {
      ...toAgentStep(wire),
      toolCalls: (wire.tool_calls ?? []).map(toToolCall),
      influencedEntities: (wire.influenced_entities ?? []).map((e) => ({
        id: String(e["id"] ?? ""),
        name: String(e["name"] ?? ""),
        type: String(e["type"] ?? ""),
        description: e["description"] as string | undefined,
        createdAt: String(e["created_at"] ?? ""),
      })) as Entity[],
    };
  }

  /** Full reasoning trace for a conversation (steps + tool calls). */
  async getTraceByConversation(conversationId: string): Promise<ConversationTrace> {
    const wire = await this.transport.request<WireConversationTrace>(
      "get_trace_by_conversation",
      { conversation_id: conversationId },
    );
    return {
      conversationId: wire.conversation_id,
      steps: (wire.steps ?? []).map(toAgentStep),
      toolCalls: (wire.tool_calls ?? []).map(toToolCall),
    };
  }

  /** All reasoning steps that influenced an entity's creation.
   *
   * Hosted REST returns the chain under `provenance`; bridge / older
   * responses use `steps`. Accept either.
   */
  async getEntityProvenance(entityId: string): Promise<EntityProvenance> {
    const wire = await this.transport.request<
      WireEntityProvenance & { provenance?: WireAgentStep[] }
    >("get_entity_provenance", { entity_id: entityId });
    const rawSteps = wire.steps ?? wire.provenance ?? [];
    return {
      entityId: wire.entity_id,
      steps: rawSteps.map(toAgentStep),
    };
  }
}
