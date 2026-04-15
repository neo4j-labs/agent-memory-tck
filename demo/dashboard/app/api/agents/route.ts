/**
 * API route returning agent status and metrics.
 *
 * Queries Neo4j directly for entity, trace, and message counts
 * scoped by agent session prefix.
 */

import { NextResponse } from "next/server";
import neo4j from "neo4j-driver";

const NEO4J_URI = process.env.NEO4J_URI ?? "bolt://localhost:7687";
const NEO4J_USER = process.env.NEO4J_USERNAME ?? "neo4j";
const NEO4J_PASS = process.env.NEO4J_PASSWORD ?? "password";

const AGENTS = [
  { name: "Lenny", prefix: "lenny", url: process.env.LENNY_URL ?? "http://localhost:8001", framework: "PydanticAI", language: "Python", color: "#3b82f6" },
  { name: "Scout", prefix: "scout", url: process.env.SCOUT_URL ?? "http://localhost:8002", framework: "Vercel AI SDK", language: "TypeScript", color: "#22c55e" },
  { name: "Forge", prefix: "forge", url: process.env.FORGE_URL ?? "http://localhost:8003", framework: "Custom HTTP", language: "Go", color: "#f97316" },
  { name: "Atlas", prefix: "atlas", url: process.env.ATLAS_URL ?? "http://localhost:8004", framework: "LangGraph", language: "Python", color: "#8b5cf6" },
  { name: "Sage", prefix: "sage", url: process.env.SAGE_URL ?? "http://localhost:8005", framework: "Semantic Kernel", language: "C#", color: "#ec4899" },
];

export const dynamic = "force-dynamic";

export async function GET() {
  let driver;
  try {
    driver = neo4j.driver(NEO4J_URI, neo4j.auth.basic(NEO4J_USER, NEO4J_PASS));
    const session = driver.session();

    try {
      // Get total counts from Neo4j
      const countsResult = await session.run(`
        OPTIONAL MATCH (e:Entity)
        WITH count(e) AS totalEntities
        OPTIONAL MATCH (t:ReasoningTrace)
        WITH totalEntities, count(t) AS totalTraces
        OPTIONAL MATCH (m:Message)
        RETURN totalEntities, totalTraces, count(m) AS totalMessages
      `);

      const record = countsResult.records[0];
      const totalEntities = record?.get("totalEntities")?.toNumber?.() ?? record?.get("totalEntities") ?? 0;
      const totalTraces = record?.get("totalTraces")?.toNumber?.() ?? record?.get("totalTraces") ?? 0;
      const totalMessages = record?.get("totalMessages")?.toNumber?.() ?? record?.get("totalMessages") ?? 0;

      // Get per-session counts
      const sessionResult = await session.run(`
        MATCH (c:Conversation)
        OPTIONAL MATCH (c)-[:HAS_MESSAGE]->(m:Message)
        RETURN c.session_id AS sessionId, count(m) AS msgCount
      `);

      const sessionCounts: Record<string, number> = {};
      for (const rec of sessionResult.records) {
        const sid = rec.get("sessionId") as string;
        const cnt = rec.get("msgCount")?.toNumber?.() ?? rec.get("msgCount") ?? 0;
        sessionCounts[sid] = cnt as number;
      }

      // Get trace counts per session
      const traceResult = await session.run(`
        MATCH (t:ReasoningTrace)
        RETURN t.session_id AS sessionId, count(t) AS traceCount
      `);

      const traceCounts: Record<string, number> = {};
      for (const rec of traceResult.records) {
        const sid = rec.get("sessionId") as string;
        const cnt = rec.get("traceCount")?.toNumber?.() ?? rec.get("traceCount") ?? 0;
        traceCounts[sid] = cnt as number;
      }

      // Map to agents by session prefix
      const agents = await Promise.all(
        AGENTS.map(async (agent) => {
          let healthy = false;
          try {
            const res = await fetch(`${agent.url}/health`, {
              signal: AbortSignal.timeout(2000),
            });
            healthy = res.ok;
          } catch {
            // not reachable
          }

          // Sum messages and traces for sessions matching this agent's prefix
          let messageCount = 0;
          let traceCount = 0;
          for (const [sid, cnt] of Object.entries(sessionCounts)) {
            if (sid.startsWith(agent.prefix)) {
              messageCount += cnt;
            }
          }
          for (const [sid, cnt] of Object.entries(traceCounts)) {
            if (sid.startsWith(agent.prefix)) {
              traceCount += cnt;
            }
          }

          return {
            name: agent.name,
            framework: agent.framework,
            language: agent.language,
            color: agent.color,
            healthy,
            entityCount: totalEntities,  // entities are shared
            traceCount,
            messageCount,
          };
        }),
      );

      return NextResponse.json({ agents });
    } finally {
      await session.close();
      await driver.close();
    }
  } catch (error) {
    // Fallback with zeros if Neo4j unavailable
    const agents = AGENTS.map((a) => ({
      name: a.name,
      framework: a.framework,
      language: a.language,
      color: a.color,
      healthy: false,
      entityCount: 0,
      traceCount: 0,
      messageCount: 0,
    }));
    return NextResponse.json({ agents });
  }
}
