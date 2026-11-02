package com.example.memory;

import com.example.controller.ChatController;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import com.example.service.TravelAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.NewMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.agentmemory.MemoryClient.await;

class NamsMemoryRestartTest {
    @Test
    void contextMessagesReachModelOnceThroughToolRoundWithoutSeparateHistoryReads() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        try (var nams = new FakeNamsServer(); var model = new FakeModelServer(request -> {
            requests.add(request);
            return requests.size() == 1
                ? "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_wishlist\",\"type\":\"function\",\"function\":{\"name\":\"getWishlist\",\"arguments\":\"{}\"}}]}"
                : "{\"role\":\"assistant\",\"content\":\"We found Old Town Guesthouse near Lucerne.\"}";
        })) {
            var configuration = new NamsMemoryConfiguration();
            var memoryClient = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
            String id = new NamsConversationHistory(memoryClient, configuration).createConversation("Lucerne hotels");
            var conversation = await(memoryClient.getConversation(UUID.fromString(id)), configuration.getWaitTimeout());
            var pastMessages = List.of(
                NewMessage.user("Find me a quiet lakeside hotel near Lucerne under 250 CHF"),
                NewMessage.assistant("Old Town Guesthouse (ID: 6), Lucerne, 120 CHF per night."),
                NewMessage.user("Remind me where we've been"),
                NewMessage.assistant("We looked at quiet lakeside hotels near Lucerne. Your wishlist is empty."));
            await(conversation.addMessages(pastMessages), configuration.getWaitTimeout());

            try (ApplicationContext context = ApplicationContext.builder()
                    .properties(model.applicationProperties(nams.url())).start()) {
                context.getBean(ChatController.class).chatGet("Where were we?", id);
            }
            model.assertHealthy();
            assertEquals(2, requests.size());
            assertEquals(0, nams.messageReads);
            for (int round = 0; round < requests.size(); round++) {
                var messages = new ObjectMapper().readTree(requests.get(round)).path("messages");
                assertEquals(round == 0 ? 2 : 4, messages.size());
                assertEquals("system", messages.get(0).path("role").asText());
                assertTrue(messages.get(0).path("content").asText()
                    .contains("friendly and knowledgeable Swiss travel advisor"));
                String prompt = messages.get(0).path("content").asText();
                String marker = "NAMS_CONTEXT_JSON:\n";
                assertTrue(prompt.contains(marker));
                var remembered = new ObjectMapper().readTree(
                    prompt.substring(prompt.indexOf(marker) + marker.length())).path("messages");
                assertEquals(4, remembered.size());
                assertEquals(List.of("assistant", "user", "assistant", "user"),
                    IntStream.range(0, 4)
                        .mapToObj(i -> remembered.get(i).path("role").asText()).toList());
                assertEquals(List.of(
                    "We looked at quiet lakeside hotels near Lucerne. Your wishlist is empty.",
                    "Remind me where we've been",
                    "Old Town Guesthouse (ID: 6), Lucerne, 120 CHF per night.",
                    "Find me a quiet lakeside hotel near Lucerne under 250 CHF"),
                    IntStream.range(0, 4)
                        .mapToObj(i -> remembered.get(i).path("content").asText()).toList());
                assertEquals("user", messages.get(1).path("role").asText());
                assertEquals("Where were we?", messages.get(1).path("content").asText());
                if (round == 1) {
                    assertEquals("call_wishlist", messages.get(2).path("tool_calls").get(0).path("id").asText());
                    assertEquals("tool", messages.get(3).path("role").asText());
                    assertEquals("call_wishlist", messages.get(3).path("tool_call_id").asText());
                }
            }
        }
    }

    @Test
    void restartRecallsPastToolActivityOnlyThroughSeededNativeContext() throws Exception {
        assertRestartBoundary(true);
    }

    @Test
    void restartWithEmptyContextNeverReconstructsToolRecordsOrSummaries() throws Exception {
        assertRestartBoundary(false);
    }

    private static void assertRestartBoundary(boolean rememberedContext) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> requests = new CopyOnWriteArrayList<>();
        var model = new FakeModelServer(request -> {
            requests.add(request);
            return calls.getAndIncrement() % 2 == 0
                ? "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"addToWishlist\",\"arguments\":\"{\\\"itemType\\\":\\\"destination\\\",\\\"itemId\\\":1}\"}}]}"
                : "{\"role\":\"assistant\",\"content\":\"Saved Zermatt.\"}";
        });
        EmbeddingService embeddings = mock(EmbeddingService.class);
        // Give Zermatt a unique best match; identical catalog vectors leave
        // membership of the five-result HNSW search dependent on tie ordering.
        when(embeddings.generateEmbedding(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            float[] vector = new float[1536];
            vector[text.equals("mountains") || text.startsWith("Zermatt ") ? 0 : 1] = 1;
            return vector;
        });
        try (model; FakeNamsServer nams = new FakeNamsServer(); HttpClient client = HttpClient.newHttpClient()) {
            if (!rememberedContext) nams.contextBodyOverride = "{}";
            String emptyId = UUID.randomUUID().toString();
            nams.seedEmptyConversation(emptyId);
            for (int run = 0; run < 2; run++) {
                try (ApplicationContext context = ApplicationContext.builder().properties(model.applicationProperties(nams.url())).singletons(embeddings).start()) {
                    EmbeddedServer app = context.getBean(EmbeddedServer.class).start();
                    assertEquals(404, send(client, app, "/api/conversations/" + UUID.randomUUID(), null).statusCode());
                    assertEquals(404, send(client, app, "/api/chat?q=hello&conversationId=" + UUID.randomUUID(), null).statusCode());
                    assertEquals(0, context.getBean(WishlistRepository.class).findAll(FakeNamsServer.ID).size());
                    if (run == 0) {
                        context.getBean(NamsConversationHistory.class).createConversation("Save Zermatt to my wishlist");
                        var empty = send(client, app, "/api/conversations/" + FakeNamsServer.ID, null);
                        assertEquals(200, empty.statusCode()); assertTrue(empty.body().contains("\"messages\":[]"), empty.body());
                        assertTrue(context.getBean(NamsConversationHistory.class).getConversation(FakeNamsServer.ID).messages().isEmpty());
                    } else {
                        var detail = send(client, app, "/api/conversations/" + FakeNamsServer.ID, null);
                        assertTrue(detail.body().contains("Save Zermatt to my wishlist"));
                        assertTrue(detail.body().contains("Saved Zermatt."));
                        assertTrue(send(client, app, "/api/conversations", null).body().contains("Save Zermatt to my wishlist"));
                        int appendsBefore = nams.appends;
                        int stepsBefore = nams.stepWrites;
                        int toolWritesBefore = nams.toolWrites;
                        int modelCallsBefore = calls.get();
                        var restored = context.getBean(ChatMemoryProvider.class).get(FakeNamsServer.ID).messages();
                        assertTrue(restored.stream().noneMatch(ToolExecutionResultMessage.class::isInstance));
                        assertTrue(restored.stream().noneMatch(message ->
                            message instanceof AiMessage ai && ai.hasToolExecutionRequests()));
                        if (rememberedContext) {
                            assertEquals(1, restored.size());
                            String prompt = assertInstanceOf(SystemMessage.class, restored.getFirst()).text();
                            assertTrue(prompt.contains("Save Zermatt to my wishlist"));
                            assertTrue(prompt.contains("Saved Zermatt."));
                            assertTrue(prompt.contains("Zermatt was selected earlier."));
                            assertTrue(prompt.contains("Continue planning from Zermatt."));
                        } else {
                            assertTrue(restored.isEmpty());
                        }
                        assertEquals(appendsBefore, nams.appends);
                        assertEquals(stepsBefore, nams.stepWrites);
                        assertEquals(toolWritesBefore, nams.toolWrites);
                        assertEquals(modelCallsBefore, calls.get());
                        assertEquals(0, nams.traceReads);
                        assertTrue(context.getBean(WishlistRepository.class).findAll(FakeNamsServer.ID).isEmpty());
                        assertEquals(2, calls.get()); // Startup and reads did not replay the prior tool turn.
                    }
                    var untouched = send(client, app, "/api/conversations/" + emptyId, null);
                    assertEquals(200, untouched.statusCode(), untouched.body());
                    assertTrue(untouched.body().contains("\"messages\":[]"), untouched.body());
                    assertTrue(context.getBean(NamsConversationHistory.class).getConversation(emptyId).messages().isEmpty());
                    String destinations = context.getBean(com.example.tools.TravelTools.class).searchDestinations("mountains");
                    assertTrue(destinations.contains("Zermatt"), destinations);
                    int requestIndex = requests.size();
                    var chat = send(client, app, run == 0 ? "/api/chat?q=Save%20Zermatt%20to%20my%20wishlist" : "/api/chat?q=Save%20Zermatt%20to%20my%20wishlist&conversationId=" + FakeNamsServer.ID, null);
                    model.assertHealthy();
                    assertEquals(200, chat.statusCode(), chat.body());
                    assertTrue(chat.body().contains(FakeNamsServer.ID));
                    assertTrue(requests.get(requestIndex).contains("friendly and knowledgeable Swiss travel advisor"));
                    if (run == 1) {
                        assertEquals(rememberedContext, requests.get(requestIndex).contains("Zermatt was selected earlier."));
                        assertEquals(rememberedContext, requests.get(requestIndex).contains("Continue planning from Zermatt."));
                        int systemCount = 0;
                        for (var message : new ObjectMapper().readTree(requests.get(requestIndex)).get("messages")) {
                            if ("system".equals(message.path("role").asText())) systemCount++;
                        }
                        assertEquals(1, systemCount);
                        assertFalse(requests.get(requestIndex).contains("call_1"));
                        assertFalse(hasToolMessages(requests.get(requestIndex)));
                    }
                    assertEquals(1, context.getBean(WishlistRepository.class).findAll(FakeNamsServer.ID).size());
                    assertTrue(send(client, app, "/api/wishlist?conversationId=" + FakeNamsServer.ID, null).body().contains("Zermatt"));
                    var assistant = context.getBean(TravelAssistant.class);
                    assertNull(assistant.getChatMemory(FakeNamsServer.ID));
                    var restoredAfterTurn = context.getBean(ChatMemoryProvider.class)
                        .get(FakeNamsServer.ID).messages();
                    assertEquals(rememberedContext ? 1 : 0, restoredAfterTurn.size());
                    assertEquals(rememberedContext,
                        restoredAfterTurn.stream().anyMatch(SystemMessage.class::isInstance));
                    assertFalse(restoredAfterTurn.stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
                    assertFalse(restoredAfterTurn.stream().anyMatch(message ->
                        message instanceof AiMessage ai && ai.hasToolExecutionRequests()));
                    assertEquals(run + 1, nams.stepWrites);
                    assertEquals(run + 1, nams.toolWrites);
                    assertEquals(0, nams.traceReads);
                    assertEquals(2, nams.creates);
                    assertEquals(0, nams.deletes);
                }
                assertEquals(0, nams.deletes);
                if (run == 0 && rememberedContext) {
                    // Context is service output: the application never derives it from recorded tools.
                    nams.observations.add(Map.of("id", UUID.randomUUID().toString(),
                        "content", "Zermatt was selected earlier."));
                    nams.reflections.add(Map.of("id", UUID.randomUUID().toString(),
                        "content", "Continue planning from Zermatt."));
                }
            }
        }
    }
    private static boolean hasToolMessages(String request) throws Exception {
        for (var message : new ObjectMapper().readTree(request).get("messages")) {
            if ("tool".equals(message.path("role").asText()) || (message.has("tool_calls") && !message.get("tool_calls").isEmpty())) return true;
        }
        return false;
    }

    private static HttpResponse<String> send(HttpClient client, EmbeddedServer server, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.getURL() + path));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
