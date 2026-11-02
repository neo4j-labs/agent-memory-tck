package com.example.memory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class NamsChatMemoryAdvisorTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private final ChatMemory memory = mock(ChatMemory.class);
    private final UserMessage user = new UserMessage("question {Zurich}");
    private final AssistantMessage answer = new AssistantMessage("answer");
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final ChatModel model = prompt -> {
        modelCalls.incrementAndGet();
        return new ChatResponse(List.of(new Generation(answer)));
    };

    private ChatClient client(ChatModel chatModel) {
        return ChatClient.builder(chatModel).defaultAdvisors(new NamsChatMemoryAdvisor(memory)).build();
    }

    private ChatClient.ChatClientRequestSpec request(ChatModel chatModel) {
        return client(chatModel).prompt().messages(user)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, ID));
    }

    @Test void preservesLeadingInstructionsOptionsAndLiteralMessages() {
        var first = new SystemMessage("travel {instructions}");
        var second = new SystemMessage("additional instructions");
        var recalled = new SystemMessage("recalled {context}");
        var history = new UserMessage("historical question");
        when(memory.get(ID)).thenReturn(List.of(recalled, history));
        ChatModel inspectingModel = new ChatModel() {
            @Override public OpenAiChatOptions getOptions() { return OpenAiChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) {
                assertEquals(List.of(first, second, recalled, history, user), prompt.getInstructions());
                assertEquals("low", ((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort());
                // The current user must be durable before the model starts.
                verify(memory).add(ID, List.of(user));
                return new ChatResponse(List.of(new Generation(answer)));
            }
        };
        assertEquals("answer", client(inspectingModel).prompt().messages(first, second, user)
            .options(OpenAiChatOptions.builder().reasoningEffort("low"))
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, ID)).call().content());
        verify(memory).get(ID);
        verify(memory).add(ID, List.of(answer));
        verifyNoMoreInteractions(memory);
    }

    @ParameterizedTest @ValueSource(strings = {"context", "user", "assistant"})
    void requiredMemoryFailuresStopTheTurn(String operation) {
        var failure = new MemoryClientException("required memory operation failed");
        when(memory.get(ID)).thenReturn(List.of());
        switch (operation) {
            case "context" -> when(memory.get(ID)).thenThrow(failure);
            case "user" -> doThrow(failure).when(memory).add(ID, List.of(user));
            case "assistant" -> doThrow(failure).when(memory).add(ID, List.of(answer));
        }
        assertSame(failure, assertThrows(MemoryClientException.class, () -> request(model).call().content()));
        assertEquals(operation.equals("assistant") ? 1 : 0, modelCalls.get());
        verify(memory).get(ID);
        if (!operation.equals("context")) verify(memory).add(ID, List.of(user));
        if (operation.equals("assistant")) verify(memory).add(ID, List.of(answer));
        verifyNoMoreInteractions(memory);
    }

    @Test void modelFailurePreservesItsCauseWithoutSavingAFinalAnswer() {
        var failure = new IllegalStateException("model failed");
        ChatModel failingModel = prompt -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> request(failingModel).call().content()));
        verify(memory).get(ID);
        verify(memory).add(ID, List.of(user));
        verifyNoMoreInteractions(memory);
    }

    @Test void missingConversationIdFailsBeforeMemoryOrModelWork() {
        assertThrows(IllegalArgumentException.class, () -> client(model).prompt().messages(user).call().content());
        assertEquals(0, modelCalls.get());
        verifyNoInteractions(memory);
    }

    @Test void missingCurrentUserFailsBeforeRecallCanSupplyAHistoricalUser() {
        assertThrows(IllegalArgumentException.class, () -> client(model).prompt().messages(new SystemMessage("instructions"))
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, ID)).call().content());
        assertEquals(0, modelCalls.get());
        verifyNoInteractions(memory);
    }

    @Test void streamingFailsBeforeMemoryOrModelWork() {
        assertThrows(UnsupportedOperationException.class,
            () -> request(model).stream().chatResponse().blockLast(Duration.ofSeconds(2)));
        assertEquals(0, modelCalls.get());
        verifyNoInteractions(memory);
    }
}
