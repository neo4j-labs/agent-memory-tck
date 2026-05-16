/**
 * Context API — returns reflections + observations + recent messages
 * for a conversation. Stub returns canned items.
 */

import { NextRequest, NextResponse } from "next/server";
import { getMemoryClient, isLive } from "@/lib/memory";

export const runtime = "nodejs";

const STUB_CONTEXT = {
  reflections: [
    {
      id: "r-stub-1",
      content: "User is exploring Neo4j Agent Memory Service via the spool demo.",
      createdAt: new Date(Date.now() - 30_000).toISOString(),
    },
  ],
  observations: [
    {
      id: "o-stub-1",
      content: "Asks questions about graph databases.",
      createdAt: new Date(Date.now() - 10_000).toISOString(),
    },
    {
      id: "o-stub-2",
      content: "Prefers concise answers.",
      createdAt: new Date(Date.now() - 5_000).toISOString(),
    },
  ],
  recentMessages: [],
};

export async function GET(req: NextRequest) {
  const conversationId = req.nextUrl.searchParams.get("conversationId");
  if (!conversationId) {
    return NextResponse.json({ reflections: [], observations: [], recentMessages: [] });
  }
  if (!isLive) {
    return NextResponse.json(STUB_CONTEXT);
  }
  try {
    const memory = getMemoryClient();
    const ctx = await memory.shortTerm.getContext(conversationId);
    return NextResponse.json(ctx);
  } catch (err) {
    return NextResponse.json({
      reflections: [],
      observations: [],
      recentMessages: [],
      error: String(err),
    });
  }
}
