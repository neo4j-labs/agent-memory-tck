import { NextResponse } from "next/server";

const AGENT_ENDPOINTS: Record<
  string,
  { url: string; mapPayload: (message: string) => { endpoint: string; body: unknown } }
> = {
  Lenny: {
    url: process.env.LENNY_URL ?? "http://localhost:8001",
    mapPayload: (message) => ({
      endpoint: "/research",
      body: { transcript: message, episode_title: "Chat session" },
    }),
  },
  Scout: {
    url: process.env.SCOUT_URL ?? "http://localhost:8002",
    mapPayload: (message) => ({
      endpoint: "/search",
      body: { query: message },
    }),
  },
  Forge: {
    url: process.env.FORGE_URL ?? "http://localhost:8003",
    mapPayload: (message) => {
      const parts = message.split(",").map((s) => s.trim());
      const entityName = parts[0] || message;
      const properties: Record<string, string> = {};
      for (let i = 1; i < parts.length; i++) {
        const [k, v] = parts[i].split("=").map((s) => s.trim());
        if (k && v) properties[k] = v;
      }
      return {
        endpoint: "/enrich",
        body: { entity_name: entityName, properties },
      };
    },
  },
  Atlas: {
    url: process.env.ATLAS_URL ?? "http://localhost:8004",
    mapPayload: (message) => ({
      endpoint: "/synthesize",
      body: { query: message },
    }),
  },
  Sage: {
    url: process.env.SAGE_URL ?? "http://localhost:8005",
    mapPayload: (message) => ({
      endpoint: "/validate",
      body: { entity_name: message },
    }),
  },
  Rune: {
    url: process.env.RUNE_URL ?? "http://localhost:8006",
    mapPayload: (message) => ({
      endpoint: "/analyze",
      body: {
        entity_names: message.split(",").map((s) => s.trim()),
        analysis_type: "summary",
      },
    }),
  },
};

export async function POST(request: Request) {
  try {
    const { agent, message } = await request.json();

    const config = AGENT_ENDPOINTS[agent];
    if (!config) {
      return NextResponse.json(
        { error: `Unknown agent: ${agent}` },
        { status: 400 },
      );
    }

    const { endpoint, body } = config.mapPayload(message);
    const url = `${config.url}${endpoint}`;

    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      return NextResponse.json(
        {
          error: `${agent} returned HTTP ${res.status}`,
          details: text,
          response: `${agent} returned an error (HTTP ${res.status}). The agent may not be running.`,
        },
        { status: 200 },
      );
    }

    const data = await res.json();

    const response = summarizeResponse(agent, data);

    return NextResponse.json({
      response,
      raw: data,
      session_id: data.session_id,
    });
  } catch (error) {
    const msg = error instanceof Error ? error.message : "Unknown error";
    return NextResponse.json({
      error: msg,
      response: `Could not reach agent. Is it running? Error: ${msg}`,
    });
  }
}

function summarizeResponse(agent: string, data: Record<string, unknown>): string {
  switch (agent) {
    case "Lenny":
      return (
        (data.result as string) ??
        `Research session created: ${data.session_id}`
      );
    case "Scout": {
      const entities = data.existingEntities as Array<Record<string, string>> | undefined;
      return entities?.length
        ? `Found ${entities.length} entities: ${entities.map((e) => e.name).join(", ")}`
        : "No entities found.";
    }
    case "Forge":
      return `Enriched entity. Facts added: ${data.facts_added ?? 0}`;
    case "Atlas":
      return (
        (data.synthesis as string) ??
        `Synthesis complete. ${data.entity_count ?? 0} entities, ${data.trace_count ?? 0} traces.`
      );
    case "Sage":
      return `Validation: ${data.found ? "Entity found" : "Entity not found"}. Conflicts: ${(data.conflicts as unknown[])?.length ?? 0}. Confidence: ${data.confidence_score ?? "N/A"}`;
    case "Rune":
      return data.result
        ? `Analysis complete (${data.analysis_type}): ${JSON.stringify(data.result)}`
        : `Analysis session: ${data.session_id}`;
    default:
      return JSON.stringify(data);
  }
}
