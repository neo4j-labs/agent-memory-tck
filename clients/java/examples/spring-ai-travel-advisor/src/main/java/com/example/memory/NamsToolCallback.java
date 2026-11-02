package com.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Captures execution evidence into the owning round without performing remote writes. */
public final class NamsToolCallback implements ToolCallback {
    private static final Logger log = LoggerFactory.getLogger(NamsToolCallback.class);
    private final ToolCallback delegate;
    private final Consumer<NewToolCall> capture;
    private final ObjectMapper mapper;

    public NamsToolCallback(ToolCallback delegate, Consumer<NewToolCall> capture, ObjectMapper mapper) {
        this.delegate = Objects.requireNonNull(delegate);
        this.capture = Objects.requireNonNull(capture);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return execute(input, () -> delegate.call(input)); }
    @Override public String call(String input, ToolContext context) {
        return execute(input, () -> delegate.call(input, context));
    }

    private String execute(String input, Supplier<String> invocation) {
        long start = System.nanoTime();
        String result;
        try {
            result = invocation.get();
        } catch (RuntimeException failure) {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            record(input, failure.getClass().getName(), ToolCallStatus.ERROR, duration);
            throw failure;
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        record(input, result, ToolCallStatus.SUCCESS, duration);
        return result;
    }

    private void record(String input, String output, ToolCallStatus status, Duration duration) {
        try {
            capture.accept(new NewToolCall(delegate.getToolDefinition().name(), input,
                    mapper.writeValueAsString(output), status, duration));
        } catch (Exception failure) {
            log.warn("NAMS tool evidence capture failed ({})", failure.getClass().getName());
        }
    }
}
