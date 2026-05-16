/**
 * Unit tests — the 12-tool MCP surface.
 */

import { describe, it, expect } from "vitest";
import { createMemoryTools } from "../../src/mcp/index.js";

describe("createMemoryTools", () => {
  const tools = createMemoryTools();

  it("returns exactly 12 tools matching memory.neo4jlabs.com/mcp", () => {
    expect(tools).toHaveLength(12);
  });

  it("uses snake_case tool names", () => {
    for (const t of tools) {
      expect(t.name).toMatch(/^memory_[a-z_]+$/);
    }
  });

  it("includes all 12 standard tools", () => {
    const names = tools.map((t) => t.name).sort();
    expect(names).toEqual(
      [
        "memory_add_entity",
        "memory_add_messages",
        "memory_create_conversation",
        "memory_explain_decision",
        "memory_get_context",
        "memory_get_entity",
        "memory_get_entity_history",
        "memory_get_trace",
        "memory_record_step",
        "memory_record_tool_call",
        "memory_search_entities",
        "memory_search_messages",
      ].sort(),
    );
  });

  it("each tool has an inputSchema with required fields", () => {
    for (const t of tools) {
      expect(t.inputSchema.type).toBe("object");
      expect(t.inputSchema.properties).toBeDefined();
    }
  });
});
