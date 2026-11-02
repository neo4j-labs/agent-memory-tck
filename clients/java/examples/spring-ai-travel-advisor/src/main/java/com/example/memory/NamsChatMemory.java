package com.example.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.MediaContent;

/** Stateless native recall and acknowledged text writes, with best-effort tool recording. */
public final class NamsChatMemory implements ChatMemory {
    static final String TOOL_CALLS_METADATA_KEY = "nams.toolCalls";
    private static final Logger log = LoggerFactory.getLogger(NamsChatMemory.class);
    private final MemoryClient client;
    private final Duration timeout;
    private final NamsContextFormatter formatter;
    private final NamsToolRecorder recorder;

    public NamsChatMemory(MemoryClient client, Duration timeout, NamsContextFormatter formatter, NamsToolRecorder recorder) {
        this.client = Objects.requireNonNull(client);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Memory timeout must be positive");
        this.timeout = timeout;
        this.formatter = Objects.requireNonNull(formatter);
        this.recorder = Objects.requireNonNull(recorder);
    }

    @Override public List<Message> get(String conversationId) {
        var conversation = MemoryClient.await(client.getConversation(UUID.fromString(conversationId)), timeout);
        return formatter.format(MemoryClient.await(conversation.context(), timeout));
    }

    @Override public void add(String conversationId, List<Message> messages) {
        List<Message> batch = List.copyOf(messages);
        List<NewMessage> text = new ArrayList<>();
        for (Message message : batch) {
            switch (message.getMessageType()) {
                case SYSTEM, TOOL -> { }
                case USER, ASSISTANT -> {
                    if (message instanceof MediaContent media && !media.getMedia().isEmpty()) throw new IllegalArgumentException("Media messages are unsupported");
                    String content = message.getText();
                    if (message instanceof AssistantMessage assistant && assistant.hasToolCalls() && (content == null || content.isBlank())) continue;
                    if (content == null || content.isBlank()) throw new IllegalArgumentException("Message text must not be blank");
                    text.add(message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER ? NewMessage.user(content) : NewMessage.assistant(content));
                }
            }
        }
        if (!text.isEmpty()) {
            var conversation = MemoryClient.await(client.getConversation(UUID.fromString(conversationId)), timeout);
            var acknowledged = MemoryClient.await(conversation.addMessages(text), timeout);
            if (acknowledged == null || acknowledged.size() != text.size()) {
                throw new MemoryClientException("Insufficient memory message acknowledgement");
            }
            for (int i = 0; i < text.size(); i++) {
                NewMessage submitted = text.get(i);
                var saved = acknowledged.get(i);
                if (saved == null || saved.id() == null || saved.role() != submitted.role() || !submitted.content().equals(saved.content())) {
                    throw new MemoryClientException("Insufficient memory message acknowledgement");
                }
            }
        }
        for (Message message : batch) {
            if (message.getMessageType() == org.springframework.ai.chat.messages.MessageType.TOOL) recordEvidence(conversationId, message);
        }
    }

    private void recordEvidence(String conversationId, Message message) {
        Object evidence = message.getMetadata().get(TOOL_CALLS_METADATA_KEY);
        if (!(evidence instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof NewToolCall))) {
            log.warn("NAMS tool evidence recording failed");
            return;
        }
        List<NewToolCall> attempts = values.stream().map(NewToolCall.class::cast).toList();
        recordToolRound(conversationId, attempts);
    }

    void recordToolRound(String conversationId, List<NewToolCall> attempts) {
        recorder.record(conversationId, List.copyOf(attempts));
    }

    @Override public void clear(String conversationId) {
        throw new UnsupportedOperationException("Native conversation history cannot be cleared through ChatMemory");
    }
}
