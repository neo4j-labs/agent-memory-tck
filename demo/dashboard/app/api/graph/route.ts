import { NextResponse } from "next/server";
import neo4j from "neo4j-driver";

const NEO4J_URI = process.env.NEO4J_URI ?? "bolt://localhost:7687";
const NEO4J_USER = process.env.NEO4J_USERNAME ?? "neo4j";
const NEO4J_PASS = process.env.NEO4J_PASSWORD ?? "password";

const LABELS = "['Entity','Person','Organization','Location','Event','Conversation','Message','Fact','Preference','ReasoningTrace','ReasoningStep','ToolCall','Tool']";

export const dynamic = "force-dynamic";

export async function GET() {
  let driver;
  try {
    driver = neo4j.driver(NEO4J_URI, neo4j.auth.basic(NEO4J_USER, NEO4J_PASS));
    const session = driver.session();
    try {
      // Nodes
      const nodesResult = await session.run(`
        MATCH (n)
        WHERE any(l IN labels(n) WHERE l IN ${LABELS})
        RETURN elementId(n) AS id,
               coalesce(n.name, n.content, n.task, n.tool_name, n.subject, n.preference, 'node') AS label,
               CASE
                 WHEN 'Person' IN labels(n) THEN 'Person'
                 WHEN 'Organization' IN labels(n) THEN 'Organization'
                 WHEN 'Location' IN labels(n) THEN 'Location'
                 WHEN 'Event' IN labels(n) THEN 'Event'
                 WHEN 'Fact' IN labels(n) THEN 'Fact'
                 WHEN 'Preference' IN labels(n) THEN 'Preference'
                 WHEN 'Conversation' IN labels(n) THEN 'Conversation'
                 WHEN 'Message' IN labels(n) THEN 'Message'
                 WHEN 'ReasoningTrace' IN labels(n) THEN 'ReasoningTrace'
                 WHEN 'ReasoningStep' IN labels(n) THEN 'ReasoningStep'
                 WHEN 'ToolCall' IN labels(n) THEN 'ToolCall'
                 WHEN 'Tool' IN labels(n) THEN 'Tool'
                 ELSE coalesce(n.type, head(labels(n)))
               END AS type,
               CASE
                 WHEN n.session_id STARTS WITH 'lenny' THEN 'lenny'
                 WHEN n.session_id STARTS WITH 'scout' THEN 'scout'
                 WHEN n.session_id STARTS WITH 'forge' THEN 'forge'
                 WHEN n.session_id STARTS WITH 'atlas' THEN 'atlas'
                 ELSE 'shared'
               END AS agent,
               properties(n) AS properties
      `);

      const nodes = nodesResult.records.map(r => ({
        id: r.get("id"),
        label: truncate(r.get("label"), 40),
        type: r.get("type"),
        agent: r.get("agent"),
        properties: cleanProps(r.get("properties")),
      }));

      // Edges
      const edgesResult = await session.run(`
        MATCH (a)-[r]->(b)
        WHERE any(l IN labels(a) WHERE l IN ${LABELS})
          AND any(l IN labels(b) WHERE l IN ${LABELS})
        RETURN elementId(r) AS id,
               elementId(a) AS source,
               elementId(b) AS target,
               type(r) AS type
      `);

      const edges = edgesResult.records.map(r => ({
        id: r.get("id"),
        source: r.get("source"),
        target: r.get("target"),
        type: r.get("type"),
      }));

      return NextResponse.json({ nodes, edges });
    } finally {
      await session.close();
      await driver.close();
    }
  } catch {
    return NextResponse.json({ nodes: [], edges: [] });
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + "\u2026" : s;
}

function cleanProps(p: Record<string, unknown>): Record<string, unknown> {
  const clean: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(p)) {
    if (k === "embedding") continue; // skip large arrays
    if (v && typeof v === "object" && "low" in (v as Record<string, unknown>)) {
      clean[k] = (v as { low: number }).low; // neo4j integer
    } else {
      clean[k] = v;
    }
  }
  return clean;
}
