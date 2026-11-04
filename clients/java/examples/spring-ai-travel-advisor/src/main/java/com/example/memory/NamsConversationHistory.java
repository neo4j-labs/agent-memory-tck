package com.example.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.exception.MemoryClientException;

/** Stateless browser projections over native, remotely stored conversations. */
public final class NamsConversationHistory {
    private final MemoryClient client;
    private final Duration timeout;

    public NamsConversationHistory(MemoryClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }
    public Conversation create(String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) throw new IllegalArgumentException("First user message cannot be blank");
        return MemoryClient.await(client.createConversation(new CreateConversation(null,
                Map.of("title", truncate(firstUserMessage.trim(), 80)))), timeout);
    }
    public Conversation require(UUID id) {
        return MemoryClient.await(client.getConversation(id), timeout);
    }
    public List<ConversationSummary> list() {
        return MemoryClient.await(client.listConversations(new ListConversations(50)), timeout).stream()
                .map(conversation -> new ConversationSummary(conversation.id().toString(), title(conversation),
                        conversation.firstMessageSnippet().filter(text -> !text.isBlank())
                                .map(text -> truncate(text.replaceAll("\\s+", " ").trim(), 96)).orElse("No messages yet"),
                        conversation.createdAt().orElseThrow(NamsConversationHistory::insufficientSummary),
                        conversation.updatedAt().orElseThrow(NamsConversationHistory::insufficientSummary)))
                .toList();
    }
    public ConversationDetail detail(UUID id) {
        Conversation conversation = require(id);
        List<Message> messages = new ArrayList<>(MemoryClient.await(conversation.messages(20), timeout));
        Collections.reverse(messages);
        return new ConversationDetail(id.toString(), title(conversation), messages.stream()
                .filter(message -> message.content() != null)
                .filter(message -> message.role() == MessageRole.USER ||
                        message.role() == MessageRole.ASSISTANT && !message.content().isBlank())
                .map(message -> new ConversationMessage(message.role() == MessageRole.USER ? "user" : "assistant", message.content()))
                .toList());
    }
    private static String title(Conversation conversation) {
        return conversation.title().filter(text -> !text.isBlank()).orElse("New Swiss adventure");
    }
    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1).stripTrailing() + "…";
    }
    private static MemoryClientException insufficientSummary() {
        return new MemoryClientException("Insufficient conversation summary data");
    }

    public record ConversationSummary(String conversationId, String title, String preview, Instant createdAt, Instant updatedAt) {}

    public record ConversationMessage(String role, String content) {}

    public record ConversationDetail(String conversationId, String title, List<ConversationMessage> messages) {}
}
