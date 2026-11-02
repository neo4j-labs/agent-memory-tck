package com.example.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/** Recalls context and records the current user and validated final answer around a synchronous tool loop. */
public final class NamsChatMemoryAdvisor implements BaseChatMemoryAdvisor {
    private final ChatMemory chatMemory;

    public NamsChatMemoryAdvisor(ChatMemory chatMemory) {
        this.chatMemory = Objects.requireNonNull(chatMemory);
    }

    @Override public int getOrder() {
        return DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String id = getConversationId(request.context());
        UserMessage current = request.prompt().getUserMessage();
        if (current.getText() == null || current.getText().isBlank()) {
            throw new IllegalArgumentException("User message must not be blank");
        }
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        int contextIndex = 0;
        while (contextIndex < messages.size() && messages.get(contextIndex) instanceof SystemMessage) contextIndex++;
        messages.addAll(contextIndex, chatMemory.get(id));
        chatMemory.add(id, List.of(current));
        return request.mutate().prompt(request.prompt().mutate().messages(messages).build()).build();
    }

    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) throw unsupportedOutput();
        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output == null || output.getText() == null || output.getText().isBlank()
                || !output.getMedia().isEmpty() || output.hasToolCalls()) throw unsupportedOutput();
        chatMemory.add(getConversationId(response.context()), List.of(output));
        return response;
    }

    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.error(new UnsupportedOperationException("NAMS chat memory supports synchronous calls only"));
    }

    private static IllegalStateException unsupportedOutput() {
        return new IllegalStateException("Model returned an unsupported final response");
    }
}
