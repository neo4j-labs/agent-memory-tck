package org.neo4j.agentmemory.reasoning;

import java.util.List;
import java.util.UUID;

public record ReasoningTrace(
        UUID conversationId,
        List<ReasoningStep> steps,
        List<ToolCall> toolCalls) {
    public ReasoningTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public List<ToolCall> toolCalls(ReasoningStep step) {
        return toolCalls.stream()
                .filter(call -> java.util.Objects.equals(call.stepId(), step.id()))
                .toList();
    }
}
