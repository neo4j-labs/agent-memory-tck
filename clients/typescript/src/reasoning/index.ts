/**
 * Reasoning memory operations.
 *
 * Provides trace management, step recording, and tool call tracking.
 * All methods correspond to Silver-tier TCK requirements.
 */

import type { Transport } from "../transport/index.js";
import type {
  CompleteTraceOptions,
  GetSimilarTracesOptions,
  ListTracesOptions,
  ReasoningStep,
  ReasoningTrace,
  RecordToolCallOptions,
  ToolCall,
  ToolCallStatus,
  ToolStats,
} from "../types.js";

/** Wire format (snake_case). */
interface WireToolCall {
  id: string;
  tool_name: string;
  arguments: Record<string, unknown>;
  result?: unknown;
  status: string;
  duration_ms?: number;
  error?: string;
}

interface WireStep {
  id: string;
  trace_id: string;
  step_number: number;
  thought?: string;
  action?: string;
  observation?: string;
  tool_calls?: WireToolCall[];
}

interface WireTrace {
  id: string;
  session_id: string;
  task: string;
  steps?: WireStep[];
  outcome?: string;
  success?: boolean;
  started_at: string;
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

function toToolCall(w: WireToolCall): ToolCall {
  return {
    id: w.id,
    toolName: w.tool_name,
    arguments: w.arguments,
    result: w.result,
    status: w.status as ToolCallStatus,
    durationMs: w.duration_ms,
    error: w.error,
  };
}

function toStep(w: WireStep): ReasoningStep {
  return {
    id: w.id,
    traceId: w.trace_id,
    stepNumber: w.step_number,
    thought: w.thought,
    action: w.action,
    observation: w.observation,
    toolCalls: (w.tool_calls ?? []).map(toToolCall),
  };
}

function toTrace(w: WireTrace): ReasoningTrace {
  return {
    id: w.id,
    sessionId: w.session_id,
    task: w.task,
    steps: (w.steps ?? []).map(toStep),
    outcome: w.outcome,
    success: w.success,
    startedAt: w.started_at,
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

export class ReasoningMemory {
  constructor(private readonly transport: Transport) {}

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
      result: options?.result,
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

  async getTraceWithSteps(
    traceId: string,
  ): Promise<ReasoningTrace | null> {
    const wire = await this.transport.request<WireTrace | null>(
      "get_trace_with_steps",
      { trace_id: traceId },
    );
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
}
