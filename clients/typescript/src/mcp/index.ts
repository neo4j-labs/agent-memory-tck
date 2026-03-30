/**
 * MCP (Model Context Protocol) tool definitions for neo4j-agent-memory.
 *
 * These tool definitions can be registered with any MCP host (Claude Desktop,
 * MCP-enabled IDEs, etc.) to provide memory operations as tool calls.
 *
 * @example
 * ```ts
 * import { MemoryClient } from "@neo4j-labs/agent-memory";
 * import { createMemoryTools, handleMemoryToolCall } from "@neo4j-labs/agent-memory/mcp";
 *
 * const client = new MemoryClient({ endpoint: "..." });
 * const tools = createMemoryTools();
 *
 * // Register tools with your MCP server
 * for (const tool of tools) {
 *   server.registerTool(tool);
 * }
 *
 * // Handle tool calls
 * const result = await handleMemoryToolCall(client, toolName, args);
 * ```
 */

import type { MemoryClient } from "../client.js";

export interface McpToolDefinition {
  name: string;
  description: string;
  inputSchema: {
    type: "object";
    properties: Record<string, unknown>;
    required?: string[];
  };
}

/**
 * Create MCP tool definitions for all memory operations.
 */
export function createMemoryTools(): McpToolDefinition[] {
  return [
    {
      name: "memory.addMessage",
      description: "Add a message to a conversation session.",
      inputSchema: {
        type: "object",
        properties: {
          sessionId: { type: "string", description: "Session identifier" },
          role: { type: "string", enum: ["user", "assistant", "system"] },
          content: { type: "string", description: "Message content" },
          metadata: {
            type: "object",
            description: "Optional metadata",
            additionalProperties: true,
          },
        },
        required: ["sessionId", "role", "content"],
      },
    },
    {
      name: "memory.getConversation",
      description: "Retrieve conversation messages by session ID.",
      inputSchema: {
        type: "object",
        properties: {
          sessionId: { type: "string", description: "Session identifier" },
          limit: { type: "number", description: "Maximum messages to return" },
        },
        required: ["sessionId"],
      },
    },
    {
      name: "memory.searchMessages",
      description: "Search messages by semantic similarity.",
      inputSchema: {
        type: "object",
        properties: {
          query: { type: "string", description: "Search query" },
          sessionId: { type: "string", description: "Optional session filter" },
          limit: { type: "number", description: "Maximum results" },
        },
        required: ["query"],
      },
    },
    {
      name: "memory.addEntity",
      description: "Create or update an entity in the knowledge graph.",
      inputSchema: {
        type: "object",
        properties: {
          name: { type: "string", description: "Entity name" },
          entityType: {
            type: "string",
            enum: ["PERSON", "ORGANIZATION", "LOCATION", "EVENT", "OBJECT"],
          },
          description: { type: "string", description: "Entity description" },
        },
        required: ["name", "entityType"],
      },
    },
    {
      name: "memory.searchEntities",
      description: "Search entities by semantic similarity.",
      inputSchema: {
        type: "object",
        properties: {
          query: { type: "string", description: "Search query" },
          limit: { type: "number", description: "Maximum results" },
        },
        required: ["query"],
      },
    },
    {
      name: "memory.addFact",
      description: "Store a subject-predicate-object fact triple.",
      inputSchema: {
        type: "object",
        properties: {
          subject: { type: "string" },
          predicate: { type: "string" },
          object: { type: "string" },
        },
        required: ["subject", "predicate", "object"],
      },
    },
    {
      name: "memory.addPreference",
      description: "Store a user preference.",
      inputSchema: {
        type: "object",
        properties: {
          category: { type: "string", description: "Preference category" },
          preference: { type: "string", description: "Preference statement" },
          context: { type: "string", description: "Contextual information" },
        },
        required: ["category", "preference"],
      },
    },
    {
      name: "memory.listSessions",
      description: "List all conversation sessions.",
      inputSchema: {
        type: "object",
        properties: {
          limit: { type: "number", description: "Maximum sessions to return" },
        },
      },
    },
  ];
}

/**
 * Handle an MCP tool call by dispatching to the appropriate MemoryClient method.
 */
export async function handleMemoryToolCall(
  client: MemoryClient,
  toolName: string,
  args: Record<string, unknown>,
): Promise<unknown> {
  switch (toolName) {
    case "memory.addMessage":
      return client.shortTerm.addMessage(
        args["sessionId"] as string,
        args["role"] as "user" | "assistant" | "system",
        args["content"] as string,
        { metadata: args["metadata"] as Record<string, unknown> | undefined },
      );

    case "memory.getConversation":
      return client.shortTerm.getConversation(args["sessionId"] as string, {
        limit: args["limit"] as number | undefined,
      });

    case "memory.searchMessages":
      return client.shortTerm.searchMessages(args["query"] as string, {
        sessionId: args["sessionId"] as string | undefined,
        limit: args["limit"] as number | undefined,
      });

    case "memory.addEntity":
      return client.longTerm.addEntity(
        args["name"] as string,
        args["entityType"] as string,
        { description: args["description"] as string | undefined },
      );

    case "memory.searchEntities":
      return client.longTerm.searchEntities(args["query"] as string, {
        limit: args["limit"] as number | undefined,
      });

    case "memory.addFact":
      return client.longTerm.addFact(
        args["subject"] as string,
        args["predicate"] as string,
        args["object"] as string,
      );

    case "memory.addPreference":
      return client.longTerm.addPreference(
        args["category"] as string,
        args["preference"] as string,
        { context: args["context"] as string | undefined },
      );

    case "memory.listSessions":
      return client.shortTerm.listSessions({
        limit: args["limit"] as number | undefined,
      });

    default:
      throw new Error(`Unknown memory tool: ${toolName}`);
  }
}
