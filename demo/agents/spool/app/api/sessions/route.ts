/**
 * Sessions API — returns conversations owned by the current cookie user.
 * Stub returns one synthetic session per visit.
 */

import { NextResponse } from "next/server";
import { getMemoryClient, isLive } from "@/lib/memory";
import { getOrCreateUserId } from "@/lib/session";

export const runtime = "nodejs";

export async function GET() {
  const userId = getOrCreateUserId();
  if (!isLive) {
    return NextResponse.json({
      sessions: [
        {
          id: `stub-${userId}`,
          title: "Stub session",
          updatedAt: new Date().toISOString(),
          messageCount: 0,
        },
      ],
    });
  }
  try {
    const memory = getMemoryClient();
    const mine = await memory.shortTerm.listConversations({ limit: 50, userId });
    return NextResponse.json({
      sessions: mine.map((c) => ({
        id: c.id,
        title: c.title ?? c.id.slice(0, 8),
        updatedAt: c.updatedAt ?? c.createdAt,
        messageCount: c.messageCount,
      })),
    });
  } catch (err) {
    return NextResponse.json({ sessions: [], error: String(err) });
  }
}
