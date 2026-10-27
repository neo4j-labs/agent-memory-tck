package org.neo4j.agentmemory.conversation;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.ReasoningStep;
import org.neo4j.agentmemory.reasoning.ReasoningTrace;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class Conversation {
    private final MemoryClient client;
    private final UUID id;
    private final String userId;
    private final Map<String, String> metadata;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String title;
    private final String firstMessageSnippet;
    private final Long messageCount;

    /**
     * Binds an existing remote identity and its supplied response details
     * to a client. Construction performs no remote operation and does not
     * verify that the remote identity exists.
     */
    public Conversation(MemoryClient client, UUID id, String userId) {
        this(client, id, userId, null, null, null, null, null, null);
    }

    /**
     * Binds an existing remote identity and its supplied response details
     * to a client. Construction performs no remote operation and does not
     * verify that the remote identity exists.
     */
    public Conversation(MemoryClient client, UUID id, String userId,
            Map<String, String> metadata, Instant createdAt, Instant updatedAt,
            String title, String firstMessageSnippet, Long messageCount) {
        this.client = client;
        this.id = id;
        this.userId = normalizeUserId(userId);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.title = title;
        this.firstMessageSnippet = firstMessageSnippet;
        this.messageCount = messageCount;
    }

    public UUID id() {
        return id;
    }

    public Optional<String> userId() {
        return Optional.ofNullable(userId);
    }

    /** Immutable wire metadata; empty when the field is absent, null or empty. */
    public Map<String, String> metadata() {
        return metadata;
    }

    /** Supplied list title, otherwise metadata.title; no display text is generated. */
    public Optional<String> title() {
        return Optional.ofNullable(title != null ? title : metadata.get("title"));
    }

    /** Creation time from this response snapshot, normalized to an instant. */
    public Optional<Instant> createdAt() {
        return Optional.ofNullable(createdAt);
    }

    /** Update time from this response snapshot; re-fetch after remote changes. */
    public Optional<Instant> updatedAt() {
        return Optional.ofNullable(updatedAt);
    }

    /** First-message snippet when supplied by the list route; never a latest preview. */
    public Optional<String> firstMessageSnippet() {
        return Optional.ofNullable(firstMessageSnippet);
    }

    /** Supplied message count; missing is distinct from zero. */
    public Optional<Long> messageCount() {
        return Optional.ofNullable(messageCount);
    }

    private static String normalizeUserId(String userId) {
        if (userId == null) {
            return null;
        }
        var normalized = userId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * @see MemoryClient#addMessage(UUID, NewMessage)
     */
    public CompletableFuture<Message> addMessage(NewMessage message) {
        return client.addMessage(id, message);
    }

    /**
     * @see MemoryClient#addMessages(UUID, List)
     */
    public CompletableFuture<List<Message>> addMessages(List<NewMessage> messages) {
        return client.addMessages(id, messages);
    }

    /**
     * Reads an immutable newest-first sequence in service order.
     * Sends no limit; the documented service default is 50.
     * @see MemoryClient#messages(UUID)
     */
    public CompletableFuture<List<Message>> messages() {
        return client.messages(id);
    }

    /**
     * Reads up to {@code limit} recent messages, newest first in service order.
     * No reversal, sorting, deduplication, pagination or remote trimming is performed.
     *
     * @param limit number of messages, from 1 through 200 inclusive
     * @throws IllegalArgumentException synchronously when limit is outside 1–200
     * @see MemoryClient#messages(UUID, int)
     */
    public CompletableFuture<List<Message>> messages(int limit) {
        return client.messages(id, limit);
    }

    /**
     * @see MemoryClient#context(UUID)
     */
    public CompletableFuture<ConversationContext> context() {
        return client.context(id);
    }

    /**
     * @see MemoryClient#recordStep(UUID, NewReasoningStep)
     */
    public CompletableFuture<ReasoningStep> recordStep(NewReasoningStep step) {
        return client.recordStep(id, step);
    }

    /**
     * @see MemoryClient#trace(UUID)
     */
    public CompletableFuture<ReasoningTrace> trace() {
        return client.trace(id);
    }

    /**
     * @see MemoryClient#deleteConversation(UUID)
     */
    public CompletableFuture<Void> delete() {
        return client.deleteConversation(id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Conversation that
                        && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
