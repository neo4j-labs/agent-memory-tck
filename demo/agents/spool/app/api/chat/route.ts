/**
 * Chat API route.
 *
 * Stub mode (default): canned responses via runStubAgent. No NAMS calls.
 * Live mode (MEMORY_API_KEY + OPENAI_API_KEY set): real Strands agent
 * wired through connectMemoryToAgent.
 *
 * Returns NDJSON-streamed events so the UI can render typing in real
 * time and pick up the conversation id for side-panel polling.
 */

import { NextRequest, NextResponse } from "next/server";
import { getMemoryClient, hasModelKey, isLive } from "@/lib/memory";
import { pickCanned } from "@/lib/stub-model";
import { getOrCreateUserId } from "@/lib/session";

export const runtime = "nodejs";

interface ChatRequest {
  message: string;
  conversationId?: string;
}

interface StreamEvent {
  type: "conversation" | "text" | "toolCall" | "done" | "error";
  data: unknown;
}

function sse(events: AsyncIterable<StreamEvent>): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      try {
        for await (const event of events) {
          controller.enqueue(encoder.encode(JSON.stringify(event) + "\n"));
        }
      } catch (err) {
        controller.enqueue(
          encoder.encode(
            JSON.stringify({ type: "error", data: { message: String(err) } }) + "\n",
          ),
        );
      } finally {
        controller.close();
      }
    },
  });
  return new Response(stream, {
    headers: {
      "Content-Type": "application/x-ndjson",
      "Cache-Control": "no-cache",
    },
  });
}

export async function POST(req: NextRequest) {
  const body = (await req.json()) as ChatRequest;
  const userMessage = body.message?.trim();
  if (!userMessage) {
    return NextResponse.json({ error: "message is required" }, { status: 400 });
  }

  const userId = getOrCreateUserId();

  // STUB MODE — fast path, no NAMS, no LLM.
  if (!isLive || !hasModelKey) {
    return sse(stubStream(userMessage, body.conversationId ?? `stub-${userId}`));
  }

  // LIVE MODE — real agent loop.
  return sse(liveStream(userMessage, body.conversationId, userId));
}

async function* stubStream(
  userMessage: string,
  conversationId: string,
): AsyncIterable<StreamEvent> {
  yield { type: "conversation", data: { id: conversationId } };
  const canned = pickCanned(userMessage);
  for (const tc of canned.toolCalls) {
    yield { type: "toolCall", data: tc };
  }
  // Token-trickle simulation so the UI animates.
  const words = canned.text.split(" ");
  for (const w of words) {
    yield { type: "text", data: w + " " };
    await new Promise((r) => setTimeout(r, 25));
  }
  yield { type: "done", data: {} };
}

async function* liveStream(
  userMessage: string,
  existingConversationId: string | undefined,
  userId: string,
): AsyncIterable<StreamEvent> {
  const memory = getMemoryClient();

  // Ensure a conversation exists.
  let conversationId = existingConversationId;
  if (!conversationId) {
    const conv = await memory.shortTerm.createConversation({
      userId,
      metadata: { source: "spool-demo" },
    });
    conversationId = conv.id;
  }
  yield { type: "conversation", data: { id: conversationId } };

  // Dynamic imports keep Strands out of stub-mode cold start.
  const { Agent, FunctionTool } = await import("@strands-agents/sdk");
  const { OpenAIModel } = await import("@strands-agents/sdk/models/openai");
  const { z } = await import("zod");
  const { connectMemoryToAgent } = await import(
    "@neo4j-labs/agent-memory/integrations/strands"
  );

  // A toy tool so the reasoning trace has something to capture. We use a
  // hand-written JSON Schema rather than zod-to-jsonschema to keep deps
  // light; Strands' FunctionTool expects raw JSON Schema for inputSchema.
  const lookupTool = new FunctionTool({
    name: "lookup_fact",
    description: "Look up a fact about a topic.",
    inputSchema: {
      type: "object",
      properties: {
        topic: { type: "string", description: "Topic to look up" },
      },
      required: ["topic"],
    },
    callback: async (input: unknown) => {
      const topic = (input as { topic?: string })?.topic ?? "unknown";
      return `Fact about ${topic}: graphs model relationships natively.`;
    },
  });
  void z;

  const { sessionManager, conversationManager } = await connectMemoryToAgent(memory, {
    conversationId,
  });

  const agent = new Agent({
    systemPrompt: "You are spool, a helpful demo agent. Be concise and curious.",
    model: new OpenAIModel({ modelId: "gpt-4o-mini" }),
    tools: [lookupTool],
    sessionManager,
    conversationManager,
  });

  // Persist the user message ourselves so it shows up in the conversation
  // even if Strands' session save batches messages.
  await memory.shortTerm.addMessage(conversationId, "user", userMessage);

  // Invoke and capture the final result. Streaming the agent's internal
  // events is doable but adds significant complexity — for the launch
  // demo we deliver the final text + emit tool-call events from the
  // reasoning trace post-hoc.
  const result = await agent.invoke(userMessage);

  // Drain any tool calls the agent made by reading the latest reasoning
  // step. Best-effort: if the reasoning fetch fails, we still ship the
  // text.
  try {
    const trace = await memory.reasoning.getTraceByConversation(conversationId);
    const latestStep = trace.steps[trace.steps.length - 1];
    if (latestStep) {
      const tcs = (trace.toolCalls ?? []).filter(
        (t) => t.stepId === latestStep.id,
      );
      for (const tc of tcs) {
        yield {
          type: "toolCall",
          data: { name: tc.toolName, input: tc.arguments, result: tc.result },
        };
      }
    }
  } catch {
    // ignore
  }

  // AgentResult exposes a `lastMessage: Message` with `content: ContentBlock[]`.
  // Flatten any text blocks into a single string for the chat UI.
  const lastMessage = (result as unknown as {
    lastMessage?: { content?: Array<{ text?: string }> };
  }).lastMessage;
  const text =
    (lastMessage?.content ?? [])
      .map((b) => b.text ?? "")
      .filter((s) => s.length > 0)
      .join("") || "(no text response)";
  yield { type: "text", data: text };
  yield { type: "done", data: {} };
}
