import { NextResponse } from "next/server";
import neo4j from "neo4j-driver";

const NEO4J_URI = process.env.NEO4J_URI ?? "bolt://localhost:7687";
const NEO4J_USER = process.env.NEO4J_USERNAME ?? "neo4j";
const NEO4J_PASS = process.env.NEO4J_PASSWORD ?? "password";

export async function POST(request: Request) {
  const { nodeId } = await request.json();
  if (!nodeId) return NextResponse.json({ connections: [] });

  let driver;
  try {
    driver = neo4j.driver(NEO4J_URI, neo4j.auth.basic(NEO4J_USER, NEO4J_PASS));
    const session = driver.session();
    try {
      const result = await session.run(`
        MATCH (n) WHERE elementId(n) = $nodeId
        OPTIONAL MATCH (n)-[r]-(m)
        RETURN collect(DISTINCT {
          id: elementId(m),
          label: coalesce(m.name, m.content, m.task, m.tool_name, m.subject, 'node'),
          type: CASE
            WHEN 'Person' IN labels(m) THEN 'Person'
            WHEN 'Organization' IN labels(m) THEN 'Organization'
            WHEN 'Fact' IN labels(m) THEN 'Fact'
            WHEN 'Message' IN labels(m) THEN 'Message'
            WHEN 'ReasoningTrace' IN labels(m) THEN 'ReasoningTrace'
            ELSE head(labels(m))
          END,
          rel: type(r),
          direction: CASE WHEN startNode(r) = n THEN 'out' ELSE 'in' END
        }) AS connections
      `, { nodeId });

      const record = result.records[0];
      const connections = (record?.get("connections") ?? []).filter(
        (c: { id: string | null }) => c.id !== null
      );

      return NextResponse.json({ connections });
    } finally {
      await session.close();
      await driver.close();
    }
  } catch {
    return NextResponse.json({ connections: [] });
  }
}
