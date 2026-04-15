using System.Text.Json.Serialization;

namespace Neo4j.AgentMemory.Models;

/// <summary>A named entity in the knowledge graph.</summary>
public class Entity
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("subtype")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Subtype { get; set; }

    [JsonPropertyName("description")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Description { get; set; }

    [JsonPropertyName("embedding")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double[]? Embedding { get; set; }

    [JsonPropertyName("canonical_name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? CanonicalName { get; set; }

    [JsonPropertyName("created_at")]
    public string CreatedAt { get; set; } = "";
}

/// <summary>A user preference.</summary>
public class Preference
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("category")]
    public string Category { get; set; } = "";

    [JsonPropertyName("preference")]
    public string PreferenceText { get; set; } = "";

    [JsonPropertyName("context")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Context { get; set; }

    [JsonPropertyName("embedding")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double[]? Embedding { get; set; }
}

/// <summary>A subject-predicate-object fact triple.</summary>
public class Fact
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("subject")]
    public string Subject { get; set; } = "";

    [JsonPropertyName("predicate")]
    public string Predicate { get; set; } = "";

    [JsonPropertyName("object")]
    public string Object { get; set; } = "";

    [JsonPropertyName("embedding")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double[]? Embedding { get; set; }
}

/// <summary>A typed relationship between two entities.</summary>
public class Relationship
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("source_id")]
    public string SourceId { get; set; } = "";

    [JsonPropertyName("target_id")]
    public string TargetId { get; set; } = "";

    [JsonPropertyName("relationship_type")]
    public string RelationshipType { get; set; } = "";

    [JsonPropertyName("properties")]
    public Dictionary<string, object?>? Properties { get; set; }
}
