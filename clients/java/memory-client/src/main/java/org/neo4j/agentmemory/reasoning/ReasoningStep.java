package org.neo4j.agentmemory.reasoning;

import org.neo4j.agentmemory.MemoryClient;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ReasoningStep {
    private final MemoryClient client;
    private final UUID id;
    private final UUID conversationId;
    private final String reasoning;
    private final String actionTaken;
    private final String result;
    private final Instant createdAt;

    /**
     * Binds an existing remote identity and its supplied response details
     * to a client. Construction performs no remote operation and does not
     * verify that the remote identity exists.
     */
    public ReasoningStep(
            MemoryClient client,
            UUID id,
            UUID conversationId,
            String reasoning,
            String actionTaken,
            String result,
            Instant createdAt) {
        this.client = client;
        this.id = id;
        this.conversationId = conversationId;
        this.reasoning = reasoning;
        this.actionTaken = actionTaken;
        this.result = result;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public String reasoning() {
        return reasoning;
    }

    public String actionTaken() {
        return actionTaken;
    }

    public Optional<String> result() {
        return Optional.ofNullable(result);
    }

    public Optional<Instant> createdAt() {
        return Optional.ofNullable(createdAt);
    }

    /**
     * @see MemoryClient#recordToolCall(UUID, NewToolCall)
     */
    public CompletableFuture<ToolCall> recordToolCall(NewToolCall call) {
        return client.recordToolCall(id, call);
    }

    /**
     * @see MemoryClient#explainReasoningStep(UUID)
     */
    public CompletableFuture<ReasoningStepExplanation> explanation() {
        return client.explainReasoningStep(id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ReasoningStep that && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
