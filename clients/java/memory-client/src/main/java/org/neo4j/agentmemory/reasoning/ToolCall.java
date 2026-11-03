package org.neo4j.agentmemory.reasoning;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ToolCall(
        UUID id,
        UUID stepId,
        String toolName,
        String input,
        String output,
        ToolCallStatus status,
        Duration duration,
        Instant createdAt) {
    public Optional<String> getOutput() {
        return Optional.ofNullable(output);
    }

    public Optional<Duration> getDuration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Instant> getCreatedAt() {
        return Optional.ofNullable(createdAt);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ToolCall that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
