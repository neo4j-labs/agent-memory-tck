import { NextResponse } from "next/server";
import neo4j from "neo4j-driver";

const NEO4J_URI = process.env.NEO4J_URI ?? "bolt://localhost:7687";
const NEO4J_USER = process.env.NEO4J_USERNAME ?? "neo4j";
const NEO4J_PASS = process.env.NEO4J_PASSWORD ?? "password";

function toNative(val: unknown): unknown {
  if (val === null || val === undefined) return val;
  if (neo4j.isInt(val)) return (val as { toNumber: () => number }).toNumber();
  if (neo4j.isDateTime(val) || neo4j.isDate(val) || neo4j.isTime(val)) {
    return (val as { toString: () => string }).toString();
  }
  if (Array.isArray(val)) return val.map(toNative);
  if (typeof val === "object") {
    const obj: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
      obj[k] = toNative(v);
    }
    return obj;
  }
  return val;
}

function detectAgent(sessionId: string | undefined): string {
  if (!sessionId) return "shared";
  for (const prefix of ["lenny", "scout", "forge", "atlas", "sage", "rune"]) {
    if (sessionId.startsWith(prefix)) return prefix;
  }
  return "shared";
}

export async function POST(request: Request) {
  const { nodeId, expand } = await request.json();
  if (!nodeId) return NextResponse.json({ connections: [] });

  let driver;
  try {
    driver = neo4j.driver(NEO4J_URI, neo4j.auth.basic(NEO4J_USER, NEO4J_PASS));
    const session = driver.session();
    try {
      const result = await session.run(
        `
        MATCH (n) WHERE elementId(n) = $nodeId
        OPTIONAL MATCH (n)-[r]-(m)
        WITH n, r, m
        WHERE m IS NOT NULL
        RETURN collect(DISTINCT {
          id: elementId(m),
          label: coalesce(m.name, m.content, m.task, m.tool_name, m.subject, m.preference, 'node'),
          type: CASE
            WHEN 'Person' IN labels(m) THEN 'Person'
            WHEN 'Organization' IN labels(m) THEN 'Organization'
            WHEN 'Location' IN labels(m) THEN 'Location'
            WHEN 'Event' IN labels(m) THEN 'Event'
            WHEN 'Fact' IN labels(m) THEN 'Fact'
            WHEN 'Message' IN labels(m) THEN 'Message'
            WHEN 'Preference' IN labels(m) THEN 'Preference'
            WHEN 'ReasoningTrace' IN labels(m) THEN 'ReasoningTrace'
            WHEN 'ReasoningStep' IN labels(m) THEN 'ReasoningStep'
            WHEN 'ToolCall' IN labels(m) THEN 'ToolCall'
            WHEN 'Conversation' IN labels(m) THEN 'Conversation'
            ELSE coalesce(m.type, head(labels(m)))
          END,
          rel: type(r),
          direction: CASE WHEN startNode(r) = n THEN 'out' ELSE 'in' END,
          relId: elementId(r),
          agent: coalesce(m.session_id, ''),
          properties: properties(m)
        }) AS connections
      `,
        { nodeId },
      );

      const record = result.records[0];
      const raw = (record?.get("connections") ?? []) as Array<
        Record<string, unknown>
      >;

      const connections = raw
        .filter((c) => c.id !== null)
        .map((c) => {
          const label = String(c.label ?? "node");
          const agent = detectAgent(c.agent as string | undefined);
          const props = c.properties
            ? toNative(c.properties) as Record<string, unknown>
            : {};

          // Clean embedding arrays from properties
          if (props && typeof props === "object") {
            delete (props as Record<string, unknown>).embedding;
            delete (props as Record<string, unknown>).task_embedding;
          }

          return {
            id: c.id,
            label: label.length > 60 ? label.slice(0, 60) + "…" : label,
            type: c.type,
            rel: c.rel,
            direction: c.direction,
            relId: c.relId,
            agent,
            ...(expand ? { properties: props } : {}),
          };
        });

      return NextResponse.json({ connections });
    } finally {
      await session.close();
      await driver.close();
    }
  } catch {
    return NextResponse.json({ connections: [] });
  }
}
