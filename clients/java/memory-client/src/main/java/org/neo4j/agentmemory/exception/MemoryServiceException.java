package org.neo4j.agentmemory.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryServiceException extends MemoryClientException {
    private final ResponseDiagnostics diagnostics;
    private final Map<String, List<String>> responseHeaders;

    public MemoryServiceException(
            String operation,
            int statusCode,
            Map<String, List<String>> responseHeaders,
            String contentType,
            String responseBodyExcerpt) {
        super(operation + " failed with HTTP " + statusCode);
        this.diagnostics = new ResponseDiagnostics(
                operation, statusCode, contentType, responseBodyExcerpt);
        this.responseHeaders = immutableHeaders(responseHeaders);
    }

    public String operation() {
        return diagnostics.operation();
    }

    public int statusCode() {
        return diagnostics.statusCode();
    }

    public Map<String, List<String>> responseHeaders() {
        return responseHeaders;
    }

    public String contentType() {
        return diagnostics.contentType();
    }

    public String responseBodyExcerpt() {
        return diagnostics.responseBodyExcerpt();
    }

    private static Map<String, List<String>> immutableHeaders(
            Map<String, List<String>> headers) {
        var copy = new LinkedHashMap<String, List<String>>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
