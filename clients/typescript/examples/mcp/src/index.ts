/**
 * Self-hosted MCP server that exposes the 12 standard neo4j-agent-memory
 * tools, backed by a `MemoryClient`. Connects via stdio — point any MCP
 * client (Claude Desktop, an MCP IDE plugin, a custom dispatcher) at the
 * resulting process.
 *
 * For most use cases you don't need to self-host: the hosted service at
 * memory.neo4jlabs.com already exposes the same tool surface at
 * https://memory.neo4jlabs.com/mcp. This example is for cases where you
 * want to wrap, log, or filter tool calls before they reach the service.
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { MemoryClient } from "@neo4j-labs/agent-memory";
import { createMemoryTools, handleMemoryToolCall } from "@neo4j-labs/agent-memory/mcp";

async function main() {
  const memory = new MemoryClient();
  const tools = createMemoryTools();

  const server = new Server(
    { name: "neo4j-agent-memory-mcp", version: "0.1.0" },
    { capabilities: { tools: {} } },
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: tools.map((t) => ({
      name: t.name,
      description: t.description,
      inputSchema: t.inputSchema,
    })),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (req) => {
    const result = await handleMemoryToolCall(
      memory,
      req.params.name,
      (req.params.arguments ?? {}) as Record<string, unknown>,
    );
    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
    };
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
  // The server stays alive on stdio until the client disconnects.
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
