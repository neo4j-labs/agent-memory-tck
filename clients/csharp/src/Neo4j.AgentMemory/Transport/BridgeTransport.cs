using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Neo4j.AgentMemory.Errors;

namespace Neo4j.AgentMemory.Transport;

/// <summary>
/// BridgeTransport — speaks the TCK bridge protocol
/// (POST {endpoint}/{snake_case_method}, snake_case JSON).
/// Used by conformance servers and the local reference adapter.
/// </summary>
public class BridgeTransport : ITransport, IDisposable
{
    private readonly string _endpoint;
    private readonly HttpClient _httpClient;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = null,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public BridgeTransport(string endpoint, string? apiKey = null, TimeSpan? timeout = null,
        IDictionary<string, string>? headers = null)
    {
        _endpoint = endpoint.TrimEnd('/');
        _httpClient = new HttpClient { Timeout = timeout ?? TimeSpan.FromSeconds(30) };

        if (!string.IsNullOrEmpty(apiKey))
        {
            _httpClient.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Bearer", apiKey);
        }
        if (headers != null)
        {
            foreach (var kv in headers)
                _httpClient.DefaultRequestHeaders.TryAddWithoutValidation(kv.Key, kv.Value);
        }
    }

    public async Task<T?> RequestAsync<T>(string method, Dictionary<string, object?>? parameters = null, CancellationToken ct = default)
    {
        var response = await SendAsync(method, parameters, ct);
        if (response.StatusCode == System.Net.HttpStatusCode.NoContent) return default;
        var json = await response.Content.ReadAsStringAsync(ct);
        if (string.IsNullOrEmpty(json) || json == "null") return default;
        return JsonSerializer.Deserialize<T>(json, JsonOptions);
    }

    public async Task RequestAsync(string method, Dictionary<string, object?>? parameters = null, CancellationToken ct = default)
    {
        await SendAsync(method, parameters, ct);
    }

    public async Task ConnectAsync(CancellationToken ct = default)
    {
        try { await RequestAsync<object>("setup", null, ct); }
        catch (HttpRequestException ex) { throw new ConnectionException($"Failed to connect to {_endpoint}", ex); }
    }

    public Task CloseAsync(CancellationToken ct = default) => Task.CompletedTask;

    private async Task<HttpResponseMessage> SendAsync(string method, Dictionary<string, object?>? parameters, CancellationToken ct)
    {
        var url = $"{_endpoint}/{method}";

        Dictionary<string, object?>? cleanParams = null;
        if (parameters != null)
        {
            cleanParams = new Dictionary<string, object?>();
            foreach (var kvp in parameters)
                if (kvp.Value != null) cleanParams[kvp.Key] = kvp.Value;
        }

        var bodyJson = cleanParams != null ? JsonSerializer.Serialize(cleanParams, JsonOptions) : "{}";
        var content = new StringContent(bodyJson, Encoding.UTF8, "application/json");

        HttpResponseMessage response;
        try { response = await _httpClient.PostAsync(url, content, ct); }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        { throw new ConnectionException($"Request to {url} timed out", ex); }
        catch (HttpRequestException ex)
        { throw new ConnectionException($"Failed to connect to {url}", ex); }

        if (response.IsSuccessStatusCode || response.StatusCode == System.Net.HttpStatusCode.NoContent)
            return response;

        var errorBody = await response.Content.ReadAsStringAsync(ct);
        var statusCode = (int)response.StatusCode;

        if (statusCode == 401 || statusCode == 403)
            throw new AuthenticationException($"Authentication failed: {errorBody}");
        if (statusCode == 404)
            throw new NotFoundException($"Not found: {errorBody}");

        throw new TransportException($"HTTP {statusCode}: {errorBody}", statusCode, errorBody);
    }

    public void Dispose()
    {
        _httpClient.Dispose();
        GC.SuppressFinalize(this);
    }
}

/// <summary>v0.1 alias for BridgeTransport. Kept for backwards compatibility.</summary>
[Obsolete("Use BridgeTransport. HttpTransport is kept for v0.1 compatibility.")]
public class HttpTransport : BridgeTransport
{
    public HttpTransport(string endpoint, string? apiKey = null, TimeSpan? timeout = null)
        : base(endpoint, apiKey, timeout) { }
}
