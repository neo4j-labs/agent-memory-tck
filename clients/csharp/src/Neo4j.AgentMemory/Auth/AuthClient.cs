using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.Auth;

/// <summary>API-key and OAuth refresh management. Hosted service only.</summary>
public class AuthClient
{
    private readonly ITransport _transport;

    internal AuthClient(ITransport transport) { _transport = transport; }

    public async Task<List<ApiKey>> ListApiKeysAsync(string workspaceId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ApiKey>>("list_api_keys", new()
        {
            ["workspace_id"] = workspaceId
        }, ct);
        return result ?? new List<ApiKey>();
    }

    public async Task<ApiKey> CreateApiKeyAsync(string label, IList<string> scopes, string workspaceId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ApiKey>("create_api_key", new()
        {
            ["label"] = label,
            ["scopes"] = scopes,
            ["workspace_id"] = workspaceId
        }, ct);
        return result!;
    }

    public async Task RevokeApiKeyAsync(string keyId, CancellationToken ct = default)
    {
        await _transport.RequestAsync("revoke_api_key", new() { ["key_id"] = keyId }, ct);
    }

    public async Task<ApiKey> RevealApiKeyAsync(string keyId, string workspaceId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ApiKey>("reveal_api_key", new()
        {
            ["key_id"] = keyId,
            ["workspace_id"] = workspaceId
        }, ct);
        return result!;
    }

    public async Task<AccessTokenPair> RefreshAccessTokenAsync(string refreshToken, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<AccessTokenPair>("refresh_access_token", new()
        {
            ["refresh_token"] = refreshToken
        }, ct);
        return result!;
    }
}
