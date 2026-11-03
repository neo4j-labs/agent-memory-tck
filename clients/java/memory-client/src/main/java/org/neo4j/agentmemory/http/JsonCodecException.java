package org.neo4j.agentmemory.http;

final class JsonCodecException extends RuntimeException {
    JsonCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
