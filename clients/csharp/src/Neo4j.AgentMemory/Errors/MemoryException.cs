namespace Neo4j.AgentMemory.Errors;

/// <summary>Base exception for all memory client errors.</summary>
public class MemoryException : Exception
{
    public MemoryException(string message) : base(message) { }
    public MemoryException(string message, Exception innerException) : base(message, innerException) { }
}

/// <summary>Thrown when an HTTP request to the memory service fails.</summary>
public class TransportException : MemoryException
{
    public int? StatusCode { get; }
    public string? ResponseBody { get; }

    public TransportException(string message, int? statusCode = null, string? responseBody = null)
        : base(message)
    {
        StatusCode = statusCode;
        ResponseBody = responseBody;
    }
}

/// <summary>Thrown when the client cannot connect to the memory service.</summary>
public class ConnectionException : MemoryException
{
    public ConnectionException(string message) : base(message) { }
    public ConnectionException(string message, Exception innerException) : base(message, innerException) { }
}

/// <summary>Thrown when authentication fails (401/403).</summary>
public class AuthenticationException : MemoryException
{
    public AuthenticationException(string message) : base(message) { }
}

/// <summary>Thrown when a requested resource is not found.</summary>
public class NotFoundException : MemoryException
{
    public NotFoundException(string message) : base(message) { }
}

/// <summary>Thrown when request validation fails.</summary>
public class ValidationException : MemoryException
{
    public ValidationException(string message) : base(message) { }
}
