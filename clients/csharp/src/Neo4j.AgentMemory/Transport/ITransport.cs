namespace Neo4j.AgentMemory.Transport;

/// <summary>
/// Transport abstraction for communicating with the memory service.
/// </summary>
public interface ITransport
{
    /// <summary>Send a request and deserialize the response.</summary>
    /// <typeparam name="T">The expected response type.</typeparam>
    /// <param name="method">The method name (maps to POST /{method}).</param>
    /// <param name="parameters">Request body parameters.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The deserialized response, or default if 204 No Content.</returns>
    Task<T?> RequestAsync<T>(string method, Dictionary<string, object?>? parameters = null, CancellationToken ct = default);

    /// <summary>Send a request with no response body expected.</summary>
    Task RequestAsync(string method, Dictionary<string, object?>? parameters = null, CancellationToken ct = default);

    /// <summary>Connect to the service (calls POST /setup).</summary>
    Task ConnectAsync(CancellationToken ct = default);

    /// <summary>Close the connection (calls POST /teardown).</summary>
    Task CloseAsync(CancellationToken ct = default);
}
