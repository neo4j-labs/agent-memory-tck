package com.example.memory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records an immutable tool execution round without waiting for native delivery. */
public final class NamsToolRecorder {
    private static final Logger log = LoggerFactory.getLogger(NamsToolRecorder.class);
    private final MemoryClient client;
    private final long timeoutNanos;

    public NamsToolRecorder(MemoryClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Memory timeout must be positive");
        }
        this.timeoutNanos = timeout.toNanos();
    }

    public CompletableFuture<Void> record(String conversationId, List<NewToolCall> attempts) {
        try {
            List<NewToolCall> batch = List.copyOf(attempts);
            if (batch.isEmpty()) return CompletableFuture.completedFuture(null);
            UUID id = UUID.fromString(conversationId);
            return nativeStage("lookup", () -> client.getConversation(id))
                    .thenCompose(conversation -> nativeStage("step", () -> conversation.recordStep(
                            new NewReasoningStep("Tool execution round", "Execute requested tools"))))
                    .thenCompose(step -> {
                        List<CompletableFuture<?>> children = batch.stream()
                                .map(attempt -> nativeStage("tool call", () -> step.recordToolCall(attempt)))
                                .collect(Collectors.toList());
                        return CompletableFuture.allOf(children.toArray(CompletableFuture[]::new));
                    });
        } catch (RuntimeException failure) {
            log.warn("NAMS round setup failed");
            return CompletableFuture.failedFuture(failure);
        }
    }

    private <T> CompletableFuture<T> nativeStage(String operation, Supplier<CompletableFuture<T>> dispatch) {
        CompletableFuture<T> completion;
        try {
            completion = dispatch.get().orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException failure) {
            completion = CompletableFuture.failedFuture(failure);
        }
        return completion.whenComplete((value, failure) -> {
            if (failure != null) log.warn("NAMS {} recording failed", operation);
        });
    }
}
