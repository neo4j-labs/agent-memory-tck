using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;
using NSubstitute;
using Xunit;
using FactAttribute = Xunit.FactAttribute;

namespace Neo4j.AgentMemory.Tests;

public class ReasoningTests
{
    private readonly ITransport _transport = Substitute.For<ITransport>();
    private readonly MemoryClient _client;

    public ReasoningTests()
    {
        _client = new MemoryClient(_transport);
    }

    [Fact]
    public async Task StartTrace_SendsSessionAndTask()
    {
        var expected = new ReasoningTrace { Id = "t-1", SessionId = "sess-1", Task = "Research", StartedAt = "2026-01-01T00:00:00Z" };
        _transport.RequestAsync<ReasoningTrace>("start_trace", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.Reasoning.StartTraceAsync("sess-1", "Research");
        Assert.Equal("t-1", result.Id);
        Assert.Equal("Research", result.Task);
    }

    [Fact]
    public async Task AddStep_SendsOptionalFields()
    {
        var expected = new ReasoningStep { Id = "s-1", TraceId = "t-1", StepNumber = 1 };
        _transport.RequestAsync<ReasoningStep>("add_step", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.Reasoning.AddStepAsync("t-1", thought: "thinking", action: "search");
        Assert.Equal("s-1", result.Id);
        Assert.Equal(1, result.StepNumber);
    }

    [Fact]
    public async Task RecordToolCall_SendsAllParams()
    {
        var expected = new ToolCall { Id = "tc-1", ToolName = "web_search", Status = ToolCallStatus.Success };
        _transport.RequestAsync<ToolCall>("record_tool_call", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.Reasoning.RecordToolCallAsync(
            "s-1", "web_search",
            new Dictionary<string, object?> { ["query"] = "test" },
            result: "found it",
            status: ToolCallStatus.Success,
            durationMs: 150);

        Assert.Equal("tc-1", result.Id);
        Assert.Equal(ToolCallStatus.Success, result.Status);
    }

    [Fact]
    public async Task GetTraceWithSteps_ReturnsNullWhenNotFound()
    {
        _transport.RequestAsync<ReasoningTrace>("get_trace_with_steps", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns((ReasoningTrace?)null);

        var result = await _client.Reasoning.GetTraceWithStepsAsync("nonexistent");
        Assert.Null(result);
    }

    [Fact]
    public async Task ListTraces_ReturnsEmptyOnNull()
    {
        _transport.RequestAsync<List<ReasoningTrace>>("list_traces", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns((List<ReasoningTrace>?)null);

        var result = await _client.Reasoning.ListTracesAsync();
        Assert.Empty(result);
    }

    [Fact]
    public async Task CompleteTrace_SendsOutcomeAndSuccess()
    {
        var expected = new ReasoningTrace { Id = "t-1", SessionId = "sess-1", Task = "Research", Outcome = "Done", Success = true, StartedAt = "2026-01-01T00:00:00Z" };
        _transport.RequestAsync<ReasoningTrace>("complete_trace", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.Reasoning.CompleteTraceAsync("t-1", outcome: "Done", success: true);
        Assert.Equal("Done", result.Outcome);
        Assert.True(result.Success);
    }
}
