/**
 * Scout — Web search agent using Hono + Vercel AI SDK.
 *
 * Scout reads entities from the shared graph, enriches them with
 * web search results, and writes discoveries back as preferences and facts.
 */

import { serve } from "@hono/node-server";
import { Hono } from "hono";
import { generateText } from "ai";
import { openai } from "@ai-sdk/openai";
import { MemoryClient } from "@neo4j-labs/agent-memory";
import { agentMemoryMiddleware } from "@neo4j-labs/agent-memory/middleware/vercel-ai";

const app = new Hono();

const MEMORY_ENDPOINT = process.env["MEMORY_ENDPOINT"] ?? "http://localhost:3001";
const PORT = parseInt(process.env["PORT"] ?? "8002", 10);

const memoryClient = new MemoryClient({ endpoint: MEMORY_ENDPOINT });

// Health check
app.get("/health", (c) =>
  c.json({ status: "healthy", agent: "scout", framework: "vercel-ai-sdk" }),
);

// Search and enrich endpoint
app.post("/search", async (c) => {
  const body = await c.req.json<{ query: string; sessionId?: string }>();
  const sessionId = body.sessionId ?? `scout-${crypto.randomUUID().slice(0, 8)}`;

  // Record the user's search request
  await memoryClient.shortTerm.addMessage(sessionId, "user", body.query);

  // Check existing knowledge
  const existingEntities = await memoryClient.longTerm.searchEntities(body.query, {
    limit: 5,
  });

  const existingContext =
    existingEntities.length > 0
      ? `Known entities: ${existingEntities.map((e) => `${e.name} (${e.type})`).join(", ")}`
      : "No existing entities found.";

  // Start a reasoning trace for this search
  const trace = await memoryClient.reasoning.startTrace(
    sessionId,
    `Web search: ${body.query}`,
  );

  const step = await memoryClient.reasoning.addStep(trace.id, {
    thought: `Searching for information about: ${body.query}`,
    action: "web_search",
  });

  // Use Vercel AI SDK with memory middleware
  const middleware = agentMemoryMiddleware(memoryClient, {
    sessionId,
    includeHistory: 10,
  });

  const result = await generateText({
    model: openai("gpt-4o-mini"),
    experimental_middleware: middleware,
    system: `You are Scout, a web search agent. Your job is to find and synthesize information.
You have access to a shared knowledge graph. ${existingContext}
Provide concise, factual answers based on your knowledge.`,
    prompt: body.query,
  });

  // Record the tool call and complete the trace
  await memoryClient.reasoning.recordToolCall(step.id, "generate_text", {
    query: body.query,
    model: "gpt-4o-mini",
  });

  await memoryClient.reasoning.completeTrace(trace.id, {
    outcome: result.text.slice(0, 200),
    success: true,
  });

  // Store the response
  await memoryClient.shortTerm.addMessage(sessionId, "assistant", result.text);

  return c.json({
    sessionId,
    result: result.text,
    existingEntities: existingEntities.map((e) => ({
      name: e.name,
      type: e.type,
    })),
  });
});

// Enrich an entity with additional information
app.post("/enrich", async (c) => {
  const body = await c.req.json<{ entityName: string }>();
  const sessionId = `scout-enrich-${crypto.randomUUID().slice(0, 8)}`;

  const entity = await memoryClient.longTerm.getEntityByName(body.entityName);
  if (!entity) {
    return c.json({ error: `Entity not found: ${body.entityName}` }, 404);
  }

  // Search for more info about this entity
  const result = await generateText({
    model: openai("gpt-4o-mini"),
    system: "Provide 2-3 factual statements about the following entity.",
    prompt: `${entity.name} (${entity.type}): ${entity.description ?? "No description"}`,
  });

  // Store enrichment as a fact
  await memoryClient.longTerm.addFact(
    entity.name,
    "ENRICHED_BY",
    `Scout: ${result.text.slice(0, 200)}`,
  );

  await memoryClient.shortTerm.addMessage(
    sessionId,
    "assistant",
    `Enriched ${entity.name}: ${result.text}`,
  );

  return c.json({
    entity: entity.name,
    enrichment: result.text,
  });
});

console.log(`Scout agent starting on port ${PORT}`);
serve({ fetch: app.fetch, port: PORT });
