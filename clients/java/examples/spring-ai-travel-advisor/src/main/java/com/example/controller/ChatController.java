package com.example.controller;

import com.example.memory.NamsConversationHistory;
import com.example.memory.NamsConversationHistory.ConversationDetail;
import com.example.memory.NamsConversationHistory.ConversationSummary;
import com.example.service.TravelAssistant;
import org.neo4j.agentmemory.conversation.Conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final TravelAssistant assistant;
    private final NamsConversationHistory history;

    public ChatController(
            TravelAssistant assistant,
            NamsConversationHistory history) {
        this.assistant = assistant;
        this.history = history;
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        return history.list();
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetail conversation(@PathVariable String conversationId) {
        return history.detail(normalizeConversationId(conversationId));
    }

    public record ChatRequest(String message, String conversationId) {}

    public record ChatReply(String conversationId, String message) {}

    @PostMapping("/chat")
    public ChatReply chat(@RequestBody ChatRequest req) {
        return chat(req.message(), req.conversationId());
    }

    @GetMapping("/chat")
    public ChatReply chatGet(
        @RequestParam("q") String query,
        @RequestParam(required=false, name="conversationId") String conversationId
    ) {
        return chat(query, conversationId);
    }

    private ChatReply chat(String message, String requestedConversationId) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }

        if (requestedConversationId == null || requestedConversationId.isBlank()) {
            Conversation conversation = history.create(message);
            return new ChatReply(conversation.id().toString(), assistant.chat(conversation, message));
        }
        UUID id = normalizeConversationId(requestedConversationId);
        Conversation conversation = history.require(id);
        return new ChatReply(id.toString(), assistant.chat(conversation, message));
    }

    private UUID normalizeConversationId(String requestedConversationId) {
        if (requestedConversationId == null || requestedConversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must be a UUID");
        }

        try {
            UUID id = UUID.fromString(requestedConversationId);
            if (!id.toString().equalsIgnoreCase(requestedConversationId)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must be a UUID");
        }
    }
}
