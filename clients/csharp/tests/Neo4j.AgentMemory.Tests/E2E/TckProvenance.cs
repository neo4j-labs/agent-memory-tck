using System.Text.Json;
using Neo4j.AgentMemory.Models;

namespace Neo4j.AgentMemory.Tests.E2E;

/// <summary>
/// Provenance tagging for e2e tests.
///
/// Every conversation, entity, and reasoning step the e2e suite creates is
/// tagged with metadata that traces it back to:
///
///   • the language client            (tck_client)
///   • the specific test              (tck_test)
///   • the GitHub Actions run         (tck_run_id, tck_run_attempt)
///   • the commit SHA + branch        (tck_sha, tck_branch)
///   • the suite start time           (tck_started_at)
///   • the runner / hostname          (tck_host)
///
/// Querying provenance after the fact (with workspace-admin Cypher access):
///
///   MATCH (c:Conversation) WHERE c.metadata.tck_run_id = '12345' RETURN c
///   MATCH (e:Entity) WHERE e.description STARTS WITH '[tck:csharp' RETURN e
///   MATCH (s:AgentStep) WHERE s.reasoning STARTS WITH 'TCK e2e' RETURN s
/// </summary>
public static class TckProvenance
{
    public const string ClientName = "csharp";

    private static readonly Lazy<Dictionary<string, object?>> _runInfo =
        new(() =>
        {
            var sha = Environment.GetEnvironmentVariable("GITHUB_SHA") ?? "local";
            if (sha.Length > 7) sha = sha[..7];
            var host = Environment.GetEnvironmentVariable("RUNNER_NAME")
                ?? Environment.MachineName;
            return new Dictionary<string, object?>
            {
                ["tck_client"] = ClientName,
                ["tck_run_id"] = Environment.GetEnvironmentVariable("GITHUB_RUN_ID") ?? "local",
                ["tck_run_attempt"] = Environment.GetEnvironmentVariable("GITHUB_RUN_ATTEMPT") ?? "1",
                ["tck_workflow"] = Environment.GetEnvironmentVariable("GITHUB_WORKFLOW") ?? "local",
                ["tck_sha"] = sha,
                ["tck_branch"] = Environment.GetEnvironmentVariable("GITHUB_REF_NAME") ?? "local",
                ["tck_started_at"] = DateTime.UtcNow.ToString("o"),
                ["tck_host"] = host,
            };
        });

    public static IReadOnlyDictionary<string, object?> RunInfo => _runInfo.Value;

    public static Dictionary<string, object?> MetadataFor(string testName, IDictionary<string, object?>? extra = null)
    {
        var output = new Dictionary<string, object?>(_runInfo.Value);
        output["tck_test"] = testName;
        if (extra != null)
        {
            foreach (var kv in extra) output[kv.Key] = kv.Value;
        }
        return output;
    }

    public static string TagDescription(string testName, string description)
    {
        var info = _runInfo.Value;
        return $"[tck:{info["tck_client"]}:{info["tck_run_id"]}:{testName}] {description}";
    }

    public static string ReasoningText(string testName, string phase = "setup")
    {
        var info = _runInfo.Value;
        return $"TCK e2e test {phase}: {testName} " +
               $"[client={info["tck_client"]}, run={info["tck_run_id"]}, " +
               $"sha={info["tck_sha"]}, branch={info["tck_branch"]}]";
    }

    public static string ResultJson(string testName, IDictionary<string, object?>? extra = null)
    {
        return JsonSerializer.Serialize(MetadataFor(testName, extra));
    }

    /// <summary>
    /// Best-effort: record a reasoning step on the conversation tying it
    /// back to the originating test. Never throws.
    /// </summary>
    public static async Task RecordStepAsync(
        MemoryClient client, string conversationId, string testName,
        string phase = "setup", string action = "create_conversation")
    {
        try
        {
            await client.Reasoning.RecordStepAsync(
                conversationId,
                reasoning: ReasoningText(testName, phase),
                actionTaken: action,
                result: ResultJson(testName, new Dictionary<string, object?>
                {
                    ["conversation_id"] = conversationId,
                }));
        }
        catch
        {
            // Provenance is best-effort.
        }
    }
}
