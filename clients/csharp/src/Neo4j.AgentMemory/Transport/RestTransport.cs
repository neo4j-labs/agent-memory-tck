using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Web;
using Neo4j.AgentMemory.Errors;

namespace Neo4j.AgentMemory.Transport;

/// <summary>
/// Token provider — returns a fresh bearer token. Used for OAuth refresh flows.
/// </summary>
public delegate Task<string> TokenProvider(CancellationToken ct);

/// <summary>
/// RestTransport — speaks the hosted Neo4j Agent Memory REST API at
/// <c>https://memory.neo4jlabs.com/v1</c>.
///
/// Maps bridge-style <c>RequestAsync(method, params)</c> calls to REST endpoints
/// with snake_case ↔ camelCase translation. Bridge methods that have no REST
/// equivalent throw <see cref="NotSupportedException"/>.
/// </summary>
public class RestTransport : ITransport, IDisposable
{
    private readonly string _endpoint;
    private readonly string? _apiKey;
    private readonly TokenProvider? _tokenProvider;
    private readonly HttpClient _httpClient;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public RestTransport(string endpoint, string? apiKey = null, TimeSpan? timeout = null,
        TokenProvider? tokenProvider = null, IDictionary<string, string>? headers = null)
    {
        _endpoint = endpoint.TrimEnd('/');
        _apiKey = apiKey;
        _tokenProvider = tokenProvider;
        _httpClient = new HttpClient { Timeout = timeout ?? TimeSpan.FromSeconds(30) };
        if (headers != null)
        {
            foreach (var kv in headers)
                _httpClient.DefaultRequestHeaders.TryAddWithoutValidation(kv.Key, kv.Value);
        }
    }

    private record Route(string HttpMethod, string Path, string[]? PathParams = null,
        string[]? QueryParams = null, bool HasBody = false,
        Func<object?, IDictionary<string, object?>, object?>? Shape = null);

    private static readonly Route Noop = new("NOOP", "");
    private static readonly Route Unsupported = new("UNSUPPORTED", "");

    private static readonly Dictionary<string, Route> Routes = new()
    {
        // Lifecycle
        ["setup"] = Noop,
        ["teardown"] = Noop,
        ["clear_all_data"] = Noop,

        // Short-Term legacy (mapped where possible)
        ["add_message"] = new("POST", "/conversations/{sessionId}/messages",
            PathParams: new[] { "sessionId" }, HasBody: true),
        ["get_conversation"] = new("GET", "/conversations/{sessionId}/messages",
            PathParams: new[] { "sessionId" }, QueryParams: new[] { "limit" },
            Shape: (raw, p) =>
            {
                object? messages = raw;
                if (raw is IDictionary<string, object?> m && m.TryGetValue("messages", out var msgs)) messages = msgs;
                return new Dictionary<string, object?>
                {
                    ["id"] = p["sessionId"],
                    ["session_id"] = p["sessionId"],
                    ["messages"] = messages,
                };
            }),
        ["list_sessions"] = new("GET", "/conversations", QueryParams: new[] { "limit" },
            Shape: (raw, _) =>
            {
                var list = new List<object?>();
                if (raw is IDictionary<string, object?> m && m.TryGetValue("conversations", out var conv) && conv is List<object?> arr)
                {
                    foreach (var item in arr)
                    {
                        if (item is IDictionary<string, object?> c)
                        {
                            list.Add(new Dictionary<string, object?>
                            {
                                ["session_id"] = (c.TryGetValue("id", out var __id) ? __id : null),
                                ["message_count"] = (c.TryGetValue("messageCount", out var __mc) ? __mc : 0) ?? 0,
                                ["created_at"] = (c.TryGetValue("createdAt", out var __ca) ? __ca : null),
                                ["updated_at"] = (c.TryGetValue("updatedAt", out var __ua) ? __ua : null),
                            });
                        }
                    }
                }
                return list;
            }),
        ["search_messages"] = new("POST", "/conversations/{sessionId}/search",
            PathParams: new[] { "sessionId" }, HasBody: true,
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("messages", out var msgs) ? msgs : raw),
        ["clear_session"] = new("DELETE", "/conversations/{sessionId}", PathParams: new[] { "sessionId" }),
        ["delete_message"] = Unsupported,

        // Long-Term legacy
        ["add_entity"] = new("POST", "/entities", HasBody: true),
        ["search_entities"] = new("POST", "/entities/search", HasBody: true,
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("entities", out var e) ? e : raw),
        ["add_preference"] = Unsupported,
        ["add_fact"] = Unsupported,
        ["search_preferences"] = Unsupported,
        ["get_entity_by_name"] = Unsupported,
        ["get_related_entities"] = Unsupported,
        ["add_relationship"] = Unsupported,
        ["merge_duplicate_entities"] = Unsupported,
        ["start_trace"] = Unsupported,
        ["add_step"] = Unsupported,
        ["record_tool_call"] = new("POST", "/reasoning/tool-calls", HasBody: true),
        ["complete_trace"] = Unsupported,
        ["get_trace_with_steps"] = Unsupported,
        ["list_traces"] = Unsupported,
        ["get_tool_stats"] = Unsupported,
        ["get_similar_traces"] = Unsupported,

        // Volume 5 / hosted-native
        ["create_conversation"] = new("POST", "/conversations", HasBody: true),
        ["list_conversations"] = new("GET", "/conversations", QueryParams: new[] { "limit" },
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("conversations", out var c) ? c : raw),
        ["get_conversation_metadata"] = new("GET", "/conversations/{conversationId}", PathParams: new[] { "conversationId" }),
        ["delete_conversation"] = new("DELETE", "/conversations/{conversationId}", PathParams: new[] { "conversationId" }),
        ["get_context"] = new("GET", "/conversations/{conversationId}/context", PathParams: new[] { "conversationId" }),
        ["bulk_add_messages"] = new("POST", "/conversations/{conversationId}/messages/bulk",
            PathParams: new[] { "conversationId" }, HasBody: true,
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("messages", out var x) ? x : raw),
        ["get_observations"] = new("GET", "/conversations/{conversationId}/observations",
            PathParams: new[] { "conversationId" }, QueryParams: new[] { "limit" },
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("observations", out var x) ? x : raw),
        ["get_reflections"] = new("GET", "/conversations/{conversationId}/reflections",
            PathParams: new[] { "conversationId" },
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("reflections", out var x) ? x : raw),
        ["list_entities"] = new("GET", "/entities", QueryParams: new[] { "type", "limit" },
            Shape: (raw, _) => raw is IDictionary<string, object?> m && m.TryGetValue("entities", out var x) ? x : raw),
        ["get_entity"] = new("GET", "/entities/{entityId}", PathParams: new[] { "entityId" }),
        ["update_entity"] = new("PUT", "/entities/{entityId}", PathParams: new[] { "entityId" }, HasBody: true),
        ["delete_entity"] = new("DELETE", "/entities/{entityId}", PathParams: new[] { "entityId" }),
        ["set_entity_feedback"] = new("PUT", "/entities/{entityId}/feedback", PathParams: new[] { "entityId" }, HasBody: true),
        ["get_entity_history"] = new("GET", "/entities/{entityId}/history", PathParams: new[] { "entityId" }),
        ["merge_entities"] = new("POST", "/entities/{sourceId}/merge", PathParams: new[] { "sourceId" }, HasBody: true),
        ["get_entity_graph"] = new("GET", "/entities/graph"),
        ["explain_step"] = new("GET", "/reasoning/explain/{stepId}", PathParams: new[] { "stepId" }),
        ["get_trace_by_conversation"] = new("GET", "/reasoning/trace/{conversationId}", PathParams: new[] { "conversationId" }),
        ["get_entity_provenance"] = new("GET", "/reasoning/provenance/{entityId}", PathParams: new[] { "entityId" }),
        ["record_step"] = new("POST", "/reasoning/steps", HasBody: true),
        ["list_steps"] = new("GET", "/reasoning/steps", QueryParams: new[] { "conversationId" }),
        ["cypher_query"] = new("POST", "/query", HasBody: true),

        // Auth
        ["list_api_keys"] = new("GET", "/auth/api-keys", QueryParams: new[] { "workspaceId" }),
        ["create_api_key"] = new("POST", "/auth/api-keys", HasBody: true),
        ["revoke_api_key"] = new("DELETE", "/auth/api-keys/{keyId}", PathParams: new[] { "keyId" }),
        ["reveal_api_key"] = new("GET", "/auth/api-keys/{keyId}/reveal",
            PathParams: new[] { "keyId" }, QueryParams: new[] { "workspaceId" }),
        ["refresh_access_token"] = new("POST", "/auth/refresh", HasBody: true),
    };

    public async Task<T?> RequestAsync<T>(string method, Dictionary<string, object?>? parameters = null, CancellationToken ct = default)
    {
        var (success, raw) = await ExecuteAsync(method, parameters, ct);
        if (!success) return default;
        if (raw == null) return default;

        // Re-marshal to typed result.
        var snake = Casing.CamelToSnake(raw);
        var json = JsonSerializer.Serialize(snake, JsonOptions);
        return JsonSerializer.Deserialize<T>(json, JsonOptions);
    }

    public async Task RequestAsync(string method, Dictionary<string, object?>? parameters = null, CancellationToken ct = default)
    {
        await ExecuteAsync(method, parameters, ct);
    }

    public async Task ConnectAsync(CancellationToken ct = default)
    {
        var req = new HttpRequestMessage(HttpMethod.Get, $"{_endpoint}/conversations?limit=1");
        await ApplyHeadersAsync(req, hasBody: false, ct);
        try
        {
            using var resp = await _httpClient.SendAsync(req, ct);
            if (resp.StatusCode == System.Net.HttpStatusCode.Unauthorized ||
                resp.StatusCode == System.Net.HttpStatusCode.Forbidden)
            {
                throw new AuthenticationException($"Authentication failed: {(int)resp.StatusCode}");
            }
        }
        catch (HttpRequestException ex)
        {
            throw new ConnectionException($"Failed to connect to {_endpoint}", ex);
        }
    }

    public Task CloseAsync(CancellationToken ct = default) => Task.CompletedTask;

    private async Task<(bool, object?)> ExecuteAsync(string method, Dictionary<string, object?>? parameters, CancellationToken ct)
    {
        if (!Routes.TryGetValue(method, out var route))
            throw new NotSupportedException($"Method '{method}' is not supported by RestTransport.");
        if (ReferenceEquals(route, Noop)) return (true, null);
        if (ReferenceEquals(route, Unsupported))
            throw new NotSupportedException($"Method '{method}' has no equivalent in the hosted REST API.");

        var camelParams = (Dictionary<string, object?>)(Casing.SnakeToCamel(parameters ?? new()) ?? new Dictionary<string, object?>());
        var consumed = new HashSet<string>();

        // Path
        var path = route.Path;
        if (route.PathParams != null)
        {
            foreach (var p in route.PathParams)
            {
                if (!camelParams.TryGetValue(p, out var v) || v == null || (v is string s && s.Length == 0))
                    throw new TransportException($"Missing path param '{p}' for method '{method}'", 400, null);
                path = path.Replace("{" + p + "}", Uri.EscapeDataString(v.ToString()!));
                consumed.Add(p);
            }
        }

        // Query
        var qs = "";
        if (route.QueryParams != null)
        {
            var pairs = new List<string>();
            foreach (var qp in route.QueryParams)
            {
                if (camelParams.TryGetValue(qp, out var v) && v != null)
                {
                    pairs.Add($"{qp}={HttpUtility.UrlEncode(v.ToString())}");
                    consumed.Add(qp);
                }
            }
            if (pairs.Count > 0) qs = "?" + string.Join("&", pairs);
        }

        // Body
        HttpContent? content = null;
        if (route.HasBody)
        {
            var body = new Dictionary<string, object?>();
            foreach (var kv in camelParams)
                if (!consumed.Contains(kv.Key) && kv.Value != null)
                    body[kv.Key] = kv.Value;
            var bodyJson = JsonSerializer.Serialize(body, JsonOptions);
            content = new StringContent(bodyJson, Encoding.UTF8, "application/json");
        }

        var req = new HttpRequestMessage(new HttpMethod(route.HttpMethod), $"{_endpoint}{path}{qs}");
        if (content != null) req.Content = content;
        await ApplyHeadersAsync(req, route.HasBody, ct);

        HttpResponseMessage resp;
        try
        {
            resp = await _httpClient.SendAsync(req, ct);
        }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        {
            throw new ConnectionException($"Request to {req.RequestUri} timed out", ex);
        }
        catch (HttpRequestException ex)
        {
            throw new ConnectionException($"Failed to call {req.RequestUri}", ex);
        }

        if (resp.StatusCode == System.Net.HttpStatusCode.Unauthorized || resp.StatusCode == System.Net.HttpStatusCode.Forbidden)
            throw new AuthenticationException($"Auth failed: {(int)resp.StatusCode}");
        if (resp.StatusCode == System.Net.HttpStatusCode.NoContent) return (true, null);

        var body2 = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
        {
            var code = (int)resp.StatusCode;
            if (code == 404) throw new NotFoundException(body2);
            throw new TransportException($"{method} failed: HTTP {code}", code, body2);
        }
        if (string.IsNullOrEmpty(body2)) return (true, null);

        var parsed = JsonSerializer.Deserialize<JsonElement>(body2);
        var raw = Casing.JsonElementToObject(parsed);
        if (route.Shape != null && raw is IDictionary<string, object?> dict)
            raw = route.Shape(raw, camelParams);
        else if (route.Shape != null) raw = route.Shape(raw, camelParams);
        return (true, raw);
    }

    private async Task ApplyHeadersAsync(HttpRequestMessage req, bool hasBody, CancellationToken ct)
    {
        if (hasBody && req.Content != null)
        {
            // Content-Type already set by StringContent
        }
        var token = _apiKey;
        if (_tokenProvider != null)
            token = await _tokenProvider(ct);
        if (!string.IsNullOrEmpty(token))
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
    }

    public void Dispose()
    {
        _httpClient.Dispose();
        GC.SuppressFinalize(this);
    }
}
