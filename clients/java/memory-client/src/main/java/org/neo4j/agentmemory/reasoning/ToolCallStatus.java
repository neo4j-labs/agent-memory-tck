package org.neo4j.agentmemory.reasoning;

public enum ToolCallStatus {
    PENDING,
    SUCCESS,
    FAILURE,
    ERROR,
    TIMEOUT,
    CANCELLED
}
