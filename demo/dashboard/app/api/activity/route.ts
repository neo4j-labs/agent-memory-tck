import { NextResponse } from "next/server";
import neo4j from "neo4j-driver";

const NEO4J_URI = process.env.NEO4J_URI ?? "bolt://localhost:7687";
const NEO4J_USER = process.env.NEO4J_USERNAME ?? "neo4j";
const NEO4J_PASS = process.env.NEO4J_PASSWORD ?? "password";

export const dynamic = "force-dynamic";

export async function GET() {
  let driver;
  try {
    driver = neo4j.driver(NEO4J_URI, neo4j.auth.basic(NEO4J_USER, NEO4J_PASS));
    const session = driver.session();
    try {
      const result = await session.run(`
        MATCH (n)
        WHERE any(l IN labels(n) WHERE l IN ['Entity','Person','Organization','Location','Event',
                                               'Fact','Message','ReasoningTrace','ToolCall','Preference'])
        RETURN elementId(n) AS id,
               coalesce(n.name, n.content, n.task, n.tool_name, n.subject, n.preference, 'item') AS label,
               CASE
                 WHEN 'Person' IN labels(n) THEN 'Person'
                 WHEN 'Organization' IN labels(n) THEN 'Organization'
                 WHEN 'Location' IN labels(n) THEN 'Location'
                 WHEN 'Event' IN labels(n) THEN 'Event'
                 WHEN 'Fact' IN labels(n) THEN 'Fact'
                 WHEN 'Message' IN labels(n) THEN 'Message'
                 WHEN 'ReasoningTrace' IN labels(n) THEN 'ReasoningTrace'
                 WHEN 'ToolCall' IN labels(n) THEN 'ToolCall'
                 ELSE head(labels(n))
               END AS type,
               CASE
                 WHEN n.session_id STARTS WITH 'lenny' THEN 'lenny'
                 WHEN n.session_id STARTS WITH 'scout' THEN 'scout'
                 WHEN n.session_id STARTS WITH 'forge' THEN 'forge'
                 WHEN n.session_id STARTS WITH 'atlas' THEN 'atlas'
                 ELSE 'shared'
               END AS agent,
               n.created_at AS ts
        ORDER BY n.created_at DESC
        LIMIT 30
      `);

      const items = result.records.map(r => ({
        id: r.get("id"),
        label: r.get("label")?.slice(0, 60) ?? "item",
        type: r.get("type"),
        agent: r.get("agent"),
      }));

      return NextResponse.json({ items });
    } finally {
      await session.close();
      await driver.close();
    }
  } catch {
    return NextResponse.json({ items: [] });
  }
}
