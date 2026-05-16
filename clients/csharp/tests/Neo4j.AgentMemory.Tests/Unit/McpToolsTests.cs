using Neo4j.AgentMemory.Mcp;
using Xunit;

namespace Neo4j.AgentMemory.Tests.Unit;

[Trait("Category", "Unit")]
public class McpToolsTests
{
    [Fact]
    public void Tools_ReturnsExactly12()
    {
        var tools = McpHandler.Tools();
        Assert.Equal(12, tools.Count);
    }

    [Fact]
    public void Tools_UseSnakeCaseNames()
    {
        var tools = McpHandler.Tools();
        foreach (var t in tools)
        {
            Assert.Matches("^memory_[a-z_]+$", t.Name);
        }
    }

    [Fact]
    public void Tools_IncludesAll12StandardNames()
    {
        var expected = new HashSet<string>
        {
            "memory_create_conversation", "memory_add_messages", "memory_get_context",
            "memory_search_messages", "memory_search_entities", "memory_get_entity",
            "memory_add_entity", "memory_get_entity_history", "memory_record_step",
            "memory_record_tool_call", "memory_get_trace", "memory_explain_decision",
        };
        var got = McpHandler.Tools().Select(t => t.Name).ToHashSet();
        Assert.Equal(expected, got);
    }

    [Fact]
    public void EachTool_HasInputSchema()
    {
        foreach (var t in McpHandler.Tools())
        {
            Assert.NotNull(t.InputSchema);
            Assert.True(t.InputSchema.ContainsKey("type"));
            Assert.True(t.InputSchema.ContainsKey("properties"));
        }
    }
}
