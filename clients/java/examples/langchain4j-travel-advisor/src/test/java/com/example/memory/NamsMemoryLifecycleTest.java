package com.example.memory;

import com.example.controller.ChatController;
import com.example.service.TravelAssistant;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamsMemoryLifecycleTest {
    @Test
    void completedToolTurnIsReleasedBeforeTheNextTurn() throws Exception {
        assertTurnBoundary(false);
    }

    @Test
    void modelFailureAfterToolResultsCannotLeakToolsIntoTheNextPrompt() throws Exception {
        assertTurnBoundary(true);
    }

    private static void assertTurnBoundary(boolean failAfterTools) throws Exception {
        try (var nams = new FakeNamsServer()) {
            var configuration = new NamsMemoryConfiguration();
            configuration.setWaitTimeout(Duration.ofSeconds(1));
            var client = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
            var history = new NamsConversationHistory(client, configuration);
            String id = history.createConversation("Read current plan");
            var provider = new NamsChatMemoryProvider(client, configuration);
            var issued = new ArrayList<ChatMemory>();
            var model = new ScriptedModel(failAfterTools);
            var assistant = AiServices.builder(TravelAssistant.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> {
                    var memory = provider.get(memoryId);
                    issued.add(memory);
                    return memory;
                })
                .tools(new PlanTools())
                .build();
            var controller = new ChatController(assistant, history);
            if (failAfterTools) {
                assertSame(model.failure, assertThrows(IllegalStateException.class,
                    () -> controller.chatGet("Read current plan", id.toUpperCase())));
            } else {
                assertEquals("Continue from Zermatt.",
                    controller.chatGet("Read current plan", id.toUpperCase()).message());
            }
            assertNull(assistant.getChatMemory(id));
            assertEquals(1, issued.size());
            assertEquals(2, model.requests.size());
            assertTrue(model.requests.get(1).messages().stream()
                .anyMatch(ToolExecutionResultMessage.class::isInstance));
            assertEquals(1, nams.stepWrites);
            assertEquals(1, nams.toolWrites);

            assertEquals("Continue from Zermatt.", controller.chatGet("Continue", id).message());
            assertNull(assistant.getChatMemory(id));
            assertEquals(2, issued.size());
            assertNotSame(issued.getFirst(), issued.getLast());
            assertEquals(3, model.requests.size());
            for (var message : model.requests.getLast().messages()) {
                assertFalse(message instanceof ToolExecutionResultMessage);
                assertFalse(message instanceof AiMessage ai && ai.hasToolExecutionRequests());
            }
            assertEquals(1, nams.stepWrites);
            assertEquals(1, nams.toolWrites);
            assertEquals(1, nams.creates);
            assertEquals(0, nams.deletes);
            assertEquals(0, nams.traceReads);
            assertFalse(history.getConversation(id).messages().isEmpty());
        }
    }

    public static final class PlanTools {
        @Tool("Read the traveler's current plan")
        public String currentPlan() {
            return "Zermatt";
        }
    }

    private static final class ScriptedModel implements ChatModel {
        private final boolean failAfterTools;
        final List<ChatRequest> requests = new ArrayList<>();
        final IllegalStateException failure = new IllegalStateException("model failed after tools");

        ScriptedModel(boolean failAfterTools) {
            this.failAfterTools = failAfterTools;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                var tool = ToolExecutionRequest.builder().id("call_current_plan")
                    .name("currentPlan").arguments("{}").build();
                return ChatResponse.builder().aiMessage(AiMessage.from(tool)).build();
            }
            if (requests.size() == 2 && failAfterTools) throw failure;
            return ChatResponse.builder().aiMessage(AiMessage.from("Continue from Zermatt.")).build();
        }
    }
}
