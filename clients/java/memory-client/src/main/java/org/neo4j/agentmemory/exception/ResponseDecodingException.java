package org.neo4j.agentmemory.exception;

public final class ResponseDecodingException extends MemoryClientException {
    private final ResponseDiagnostics diagnostics;

    public ResponseDecodingException(
            String operation,
            int statusCode,
            String contentType,
            String responseBodyExcerpt,
            Throwable cause) {
        super(operation + " could not decode HTTP " + statusCode + " response", cause);
        this.diagnostics = new ResponseDiagnostics(
                operation, statusCode, contentType, responseBodyExcerpt);
    }

    public String operation() {
        return diagnostics.operation();
    }

    public int statusCode() {
        return diagnostics.statusCode();
    }

    public String contentType() {
        return diagnostics.contentType();
    }

    public String responseBodyExcerpt() {
        return diagnostics.responseBodyExcerpt();
    }
}
