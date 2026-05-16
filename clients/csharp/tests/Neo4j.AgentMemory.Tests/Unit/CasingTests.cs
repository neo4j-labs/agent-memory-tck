using Neo4j.AgentMemory.Transport;
using Xunit;

namespace Neo4j.AgentMemory.Tests.Unit;

[Trait("Category", "Unit")]
public class CasingTests
{
    [Theory]
    [InlineData("", "")]
    [InlineData("id", "id")]
    [InlineData("user_id", "userId")]
    [InlineData("created_at", "createdAt")]
    [InlineData("source_stage", "sourceStage")]
    public void ToCamel_Conversions(string snake, string expected)
    {
        Assert.Equal(expected, Casing.ToCamel(snake));
    }

    [Theory]
    [InlineData("", "")]
    [InlineData("id", "id")]
    [InlineData("userId", "user_id")]
    [InlineData("createdAt", "created_at")]
    [InlineData("sourceStage", "source_stage")]
    public void ToSnake_Conversions(string camel, string expected)
    {
        Assert.Equal(expected, Casing.ToSnake(camel));
    }

    [Fact]
    public void SnakeToCamel_NestedDict()
    {
        var input = new Dictionary<string, object?>
        {
            ["user_id"] = "alice",
            ["metadata"] = new Dictionary<string, object?> { ["is_active"] = true }
        };
        var result = (Dictionary<string, object?>)Casing.SnakeToCamel(input)!;

        Assert.Equal("alice", result["userId"]);
        var meta = (Dictionary<string, object?>)result["metadata"]!;
        Assert.Equal(true, meta["isActive"]);
    }

    [Fact]
    public void CamelToSnake_NestedDict()
    {
        var input = new Dictionary<string, object?>
        {
            ["userId"] = "alice",
            ["metadata"] = new Dictionary<string, object?> { ["isActive"] = true }
        };
        var result = (Dictionary<string, object?>)Casing.CamelToSnake(input)!;

        Assert.Equal("alice", result["user_id"]);
        var meta = (Dictionary<string, object?>)result["metadata"]!;
        Assert.Equal(true, meta["is_active"]);
    }

    [Fact]
    public void Casing_IgnoresPrimitives()
    {
        Assert.Equal("hello", Casing.SnakeToCamel("hello"));
        Assert.Equal(42, Casing.SnakeToCamel(42));
        Assert.Null(Casing.SnakeToCamel(null));
    }

    [Fact]
    public void Casing_RoundTripIdempotent()
    {
        var original = new Dictionary<string, object?>
        {
            ["user_id"] = "alice",
            ["nested"] = new Dictionary<string, object?> { ["is_active"] = true }
        };
        var roundtrip = (Dictionary<string, object?>)Casing.CamelToSnake(Casing.SnakeToCamel(original))!;
        Assert.Equal("alice", roundtrip["user_id"]);
        var nested = (Dictionary<string, object?>)roundtrip["nested"]!;
        Assert.Equal(true, nested["is_active"]);
    }
}
