/**
 * API route returning agent status and metrics.
 */

import { NextResponse } from "next/server";

const AGENT_ENDPOINTS = [
  { name: "Lenny", url: process.env.LENNY_URL ?? "http://localhost:8001", framework: "PydanticAI", language: "Python", color: "#3b82f6" },
  { name: "Scout", url: process.env.SCOUT_URL ?? "http://localhost:8002", framework: "Vercel AI SDK", language: "TypeScript", color: "#22c55e" },
  { name: "Forge", url: process.env.FORGE_URL ?? "http://localhost:8003", framework: "Custom HTTP", language: "Go", color: "#f97316" },
  { name: "Atlas", url: process.env.ATLAS_URL ?? "http://localhost:8004", framework: "LangGraph", language: "Python", color: "#8b5cf6" },
];

export async function GET() {
  const agents = await Promise.all(
    AGENT_ENDPOINTS.map(async (agent) => {
      let healthy = false;
      try {
        const res = await fetch(`${agent.url}/health`, {
          signal: AbortSignal.timeout(3000),
        });
        healthy = res.ok;
      } catch {
        // Agent not reachable
      }

      return {
        ...agent,
        healthy,
        entityCount: 0,
        traceCount: 0,
        messageCount: 0,
      };
    }),
  );

  return NextResponse.json({ agents });
}
