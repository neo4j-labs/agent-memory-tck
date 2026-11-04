package com.example.controller;

import com.example.memory.ConversationIds;
import com.example.memory.NamsConversationHistory;
import com.example.service.TravelAssistant;
import io.micronaut.http.MediaType;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Controller("/api")
public class ChatController {

    private final TravelAssistant assistant;
    private final NamsConversationHistory history;

    public ChatController(TravelAssistant assistant, NamsConversationHistory history) {
        this.assistant = assistant;
        this.history = history;
    }

    @Get(uri = "/conversations", produces = MediaType.APPLICATION_JSON)
    public List<NamsConversationHistory.ConversationSummary> conversations() {
        return history.listConversations();
    }

    @Get(uri = "/conversations/{conversationId}", produces = MediaType.APPLICATION_JSON)
    public NamsConversationHistory.ConversationDetail conversation(@PathVariable String conversationId) {
        String id = ConversationIds.parse(conversationId).toString();
        return history.getConversation(id);
    }

    @Serdeable
    public record ChatRequest(String message, @Nullable String conversationId) {}

    @Serdeable
    public record ChatReply(String conversationId, String message) {}

    @Post(uri = "/chat", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public ChatReply chat(@Body ChatRequest req) {
        return chat(req.message(), req.conversationId());
    }

    @Get(uri = "/chat", produces = MediaType.APPLICATION_JSON)
    public ChatReply chatGet(
        @QueryValue("q") String query,
        @Nullable @QueryValue("conversationId") String conversationId
    ) {
        return chat(query, conversationId);
    }

    private ChatReply chat(String message, @Nullable String requestedConversationId) {
        if (message == null || message.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }

        boolean supplied = requestedConversationId != null && !requestedConversationId.isBlank();
        String conversationId = supplied ? ConversationIds.parse(requestedConversationId).toString()
                : history.createConversation(message);
        if (supplied) history.requireConversation(conversationId);
        try {
            return new ChatReply(conversationId, assistant.chat(conversationId, message));
        } finally {
            assistant.evictChatMemory(conversationId);
        }
    }

}
