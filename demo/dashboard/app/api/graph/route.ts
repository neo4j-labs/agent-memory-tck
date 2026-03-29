/**
 * API route that fetches graph data from Neo4j for visualization.
 */

import { NextResponse } from "next/server";
import neo4j from "neo4j-driver";

const NEO4J_URI = process.env.NEO4J_URI ?? "bolt://localhost:7687";
const NEO4J_USER = process.env.NEO4J_USERNAME ?? "neo4j";
const NEO4J_PASS = process.env.NEO4J_PASSWORD ?? "password";

export async function GET() {
  try {
    const driver = neo4j.driver(NEO4J_URI, neo4j.auth.basic(NEO4J_USER, NEO4J_PASS));
    const session = driver.session();

    try {
      // Fetch entities and their relationships
      const result = await session.run(`
        MATCH (n)
        WHERE n:Entity OR n:Person OR n:Organization OR n:Location OR n:Event
        OPTIONAL MATCH (n)-[r]->(m)
        WHERE m:Entity OR m:Person OR m:Organization OR m:Location OR m:Event
        RETURN
          collect(DISTINCT {
            id: elementId(n),
            label: n.name,
            type: CASE
              WHEN 'Person' IN labels(n) THEN 'PERSON'
              WHEN 'Organization' IN labels(n) THEN 'ORGANIZATION'
              WHEN 'Location' IN labels(n) THEN 'LOCATION'
              WHEN 'Event' IN labels(n) THEN 'EVENT'
              ELSE coalesce(n.type, 'OBJECT')
            END,
            agent: CASE
              WHEN n.created_by IS NOT NULL THEN n.created_by
              ELSE 'unknown'
            END
          }) AS nodes,
          collect(DISTINCT CASE WHEN r IS NOT NULL THEN {
            id: elementId(r),
            source: elementId(n),
            target: elementId(m),
            type: type(r)
          } END) AS edges
      `);

      const record = result.records[0];
      const nodes = record?.get("nodes") ?? [];
      const edges = (record?.get("edges") ?? []).filter(Boolean);

      return NextResponse.json({ nodes, edges });
    } finally {
      await session.close();
      await driver.close();
    }
  } catch (error) {
    // Return empty graph if Neo4j is not available
    return NextResponse.json({ nodes: [], edges: [] });
  }
}
