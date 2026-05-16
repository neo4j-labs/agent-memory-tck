/**
 * Reasoning trace API — returns ReasoningSteps + ToolCalls for a
 * conversation. In stub mode, returns canned data so the panel renders
 * without keys.
 */

import { NextRequest, NextResponse } from "next/server";
import { getMemoryClient, isLive } from "@/lib/memory";

export const runtime = "nodejs";

const STUB_TRACE = {
  steps: [
    {
      id: "step-stub-1",
      reasoning: "agent invocation started",
      actionTaken: "invoke_agent",
      result: undefined,
      createdAt: new Date(Date.now() - 4000).toISOString(),
    },
    {
      id: "step-stub-2",
      reasoning: "agent invocation completed",
      actionTaken: "invocation_complete",
      result: "ok",
      createdAt: new Date(Date.now() - 1000).toISOString(),
    },
  ],
  toolCalls: [
    {
      id: "tc-stub-1",
      stepId: "step-stub-1",
      toolName: "lookup_fact",
      arguments: { topic: "Neo4j" },
      status: "success",
      durationMs: 23,
    },
  ],
};

export async function GET(req: NextRequest) {
  const conversationId = req.nextUrl.searchParams.get("conversationId");
  if (!conversationId) return NextResponse.json({ steps: [], toolCalls: [] });

  if (!isLive) {
    return NextResponse.json(STUB_TRACE);
  }

  try {
    const memory = getMemoryClient();
    const trace = await memory.reasoning.getTraceByConversation(conversationId);
    return NextResponse.json({
      steps: trace.steps ?? [],
      toolCalls: trace.toolCalls ?? [],
    });
  } catch (err) {
    return NextResponse.json(
      { error: String(err), steps: [], toolCalls: [] },
      { status: 200 },
    );
  }
}
