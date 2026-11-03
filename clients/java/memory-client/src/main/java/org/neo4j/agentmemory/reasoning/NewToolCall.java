package org.neo4j.agentmemory.reasoning;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record NewToolCall(
        String toolName,
        String input,
        String output,
        ToolCallStatus status,
        Duration duration) {
    private NewToolCall(Builder builder) {
        this(
                builder.toolName,
                builder.input,
                builder.output,
                builder.status,
                builder.duration);
    }

    public Optional<String> getOutput() {
        return Optional.ofNullable(output);
    }

    public Optional<Duration> getDuration() {
        return Optional.ofNullable(duration);
    }

    public static Builder builder(String toolName, String input) {
        return new Builder(toolName, input);
    }

    public static final class Builder {
        private final String toolName;
        private final String input;
        private String output;
        private ToolCallStatus status = ToolCallStatus.SUCCESS;
        private Duration duration;

        private Builder(String toolName, String input) {
            this.toolName = toolName;
            this.input = input;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder status(ToolCallStatus status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public NewToolCall build() {
            return new NewToolCall(this);
        }
    }
}
