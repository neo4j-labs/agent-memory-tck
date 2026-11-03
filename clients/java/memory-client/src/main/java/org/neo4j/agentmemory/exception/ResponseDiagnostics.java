package org.neo4j.agentmemory.exception;

record ResponseDiagnostics(
        String operation,
        int statusCode,
        String contentType,
        String responseBodyExcerpt) {}
