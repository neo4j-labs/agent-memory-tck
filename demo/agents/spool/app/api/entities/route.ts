/**
 * Entities API — returns entities from the long-term graph.
 * Stub returns canned entities for the demo-without-keys path.
 */

import { NextResponse } from "next/server";
import { getMemoryClient, isLive } from "@/lib/memory";

export const runtime = "nodejs";

const STUB_ENTITIES = [
  { id: "e-stub-1", name: "Neo4j", type: "concept", description: "Native graph database." },
  { id: "e-stub-2", name: "NAMS", type: "concept", description: "Neo4j Agent Memory Service." },
  { id: "e-stub-3", name: "Strands", type: "concept", description: "AWS Agents SDK." },
];

export async function GET() {
  if (!isLive) {
    return NextResponse.json({ entities: STUB_ENTITIES });
  }
  try {
    const memory = getMemoryClient();
    const entities = await memory.longTerm.listEntities({ limit: 25 });
    return NextResponse.json({ entities });
  } catch (err) {
    return NextResponse.json({ entities: [], error: String(err) });
  }
}
