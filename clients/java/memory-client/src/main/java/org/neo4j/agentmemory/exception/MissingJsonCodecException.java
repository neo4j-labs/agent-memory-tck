package org.neo4j.agentmemory.exception;

public final class MissingJsonCodecException extends MemoryClientException {
    public MissingJsonCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
