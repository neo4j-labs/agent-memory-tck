package com.example.memory;

import jakarta.inject.Singleton;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import static org.neo4j.agentmemory.MemoryClient.await;

/** Projects bounded NAMS conversation reads into the public conversation DTOs. */
@Singleton
public class NamsConversationHistory {
    private final MemoryClient client;
    private final Duration waitTimeout;

    public NamsConversationHistory(MemoryClient client, NamsMemoryConfiguration configuration) {
        this.client = client;
        this.waitTimeout = configuration.getWaitTimeout();
    }

    public String createConversation(String firstUserMessage) {
        String title = firstUserMessage == null ? "" : firstUserMessage.trim();
        title = title.isEmpty() ? "New Swiss adventure" : truncate(title, 80);
        return await(client.createConversation(new CreateConversation(null,
            Map.of("title", title))), waitTimeout).id().toString();
    }

    public void requireConversation(String conversationId) {
        await(client.getConversation(ConversationIds.parse(conversationId)), waitTimeout);
    }

    public List<ConversationSummary> listConversations() {
        Comparator<Instant> descendingTime = Comparator.nullsLast(Comparator.reverseOrder());
        return await(client.listConversations(new ListConversations(50)), waitTimeout).stream().map(conversation -> new ConversationSummary(
            conversation.id().toString(), title(conversation), preview(conversation),
            conversation.createdAt().orElse(null), conversation.updatedAt().orElse(null)))
            .sorted(Comparator.comparing(ConversationSummary::updatedAt, descendingTime)
                .thenComparing(ConversationSummary::createdAt, descendingTime)
                .thenComparing(ConversationSummary::conversationId, Comparator.reverseOrder()))
            .toList();
    }

    public ConversationDetail getConversation(String conversationId) {
        UUID id = ConversationIds.parse(conversationId);
        var conversation = await(client.getConversation(id), waitTimeout);
        var chronological = new ArrayList<>(await(conversation.messages(20), waitTimeout));
        Collections.reverse(chronological);
        var messages = chronological.stream().filter(NamsConversationHistory::visible)
            .map(message -> new ConversationMessage(
                message.role() == MessageRole.USER ? "user" : "assistant", message.content())).toList();
        return new ConversationDetail(id.toString(), title(conversation), messages);
    }

    private String preview(Conversation conversation) {
        return await(conversation.messages(1), waitTimeout).stream()
            .filter(NamsConversationHistory::visible)
            .map(message -> message.content().replaceAll("\\s+", " ").trim())
            .filter(content -> !content.isEmpty())
            .map(content -> truncate(content, 96))
            .findFirst().orElse("No messages yet");
    }

    private static boolean visible(Message message) {
        return (message.role() == MessageRole.USER || message.role() == MessageRole.ASSISTANT)
            && message.content() != null
            && (message.role() != MessageRole.ASSISTANT || !message.content().isBlank());
    }

    private static String title(Conversation conversation) {
        String title = conversation.metadata().get("title");
        return title == null || title.isBlank() ? "New Swiss adventure" : title;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1).stripTrailing() + "…";
    }

    @Serdeable
    public record ConversationSummary(String conversationId, String title,
                               String preview, Instant createdAt,
                               Instant updatedAt) {}
    @Serdeable
    public record ConversationMessage(String role, String content) {}
    @Serdeable
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record ConversationDetail(String conversationId, String title,
                              List<ConversationMessage> messages) {}
}
