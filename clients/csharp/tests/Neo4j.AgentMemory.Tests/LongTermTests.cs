using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;
using NSubstitute;

namespace Neo4j.AgentMemory.Tests;

public class LongTermTests
{
    private readonly ITransport _transport = Substitute.For<ITransport>();
    private readonly MemoryClient _client;

    public LongTermTests()
    {
        _client = new MemoryClient(_transport);
    }

    [Fact]
    public async Task AddEntity_SendsCorrectParameters()
    {
        var expected = new Entity { Id = "e-1", Name = "Alice", Type = "PERSON", CreatedAt = "2026-01-01T00:00:00Z" };
        _transport.RequestAsync<Entity>("add_entity", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.LongTerm.AddEntityAsync("Alice", "PERSON", "An engineer");
        Assert.Equal("e-1", result.Id);
        Assert.Equal("Alice", result.Name);
    }

    [Fact]
    public async Task AddFact_SendsSubjectPredicateObj()
    {
        var expected = new Fact { Id = "f-1", Subject = "Alice", Predicate = "WORKS_AT", Object = "Acme" };
        _transport.RequestAsync<Fact>("add_fact", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.LongTerm.AddFactAsync("Alice", "WORKS_AT", "Acme");
        Assert.Equal("Alice", result.Subject);
        Assert.Equal("WORKS_AT", result.Predicate);
        Assert.Equal("Acme", result.Object);
    }

    [Fact]
    public async Task GetEntityByName_ReturnsNullWhenNotFound()
    {
        _transport.RequestAsync<Entity>("get_entity_by_name", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns((Entity?)null);

        var result = await _client.LongTerm.GetEntityByNameAsync("Unknown");
        Assert.Null(result);
    }

    [Fact]
    public async Task SearchEntities_ReturnsEmptyListOnNull()
    {
        _transport.RequestAsync<List<Entity>>("search_entities", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns((List<Entity>?)null);

        var result = await _client.LongTerm.SearchEntitiesAsync("test");
        Assert.Empty(result);
    }

    [Fact]
    public async Task AddPreference_WithContext()
    {
        var expected = new Preference { Id = "p-1", Category = "theme", PreferenceText = "dark mode" };
        _transport.RequestAsync<Preference>("add_preference", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.LongTerm.AddPreferenceAsync("theme", "dark mode", "for coding");
        Assert.Equal("p-1", result.Id);
        Assert.Equal("theme", result.Category);
    }
}
