package org.neo4j.agentmemory.reasoning;

import org.neo4j.agentmemory.entity.Entity;

import java.util.List;

public record ReasoningStepExplanation(
        ReasoningStep step,
        List<ToolCall> toolCalls,
        List<Entity> influencedEntities) {
    public ReasoningStepExplanation {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        influencedEntities =
                influencedEntities == null ? List.of() : List.copyOf(influencedEntities);
    }
}
