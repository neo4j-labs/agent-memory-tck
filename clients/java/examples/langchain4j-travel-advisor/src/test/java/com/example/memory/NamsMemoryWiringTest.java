package com.example.memory;

import com.example.repository.JdbcDataSources;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import com.example.service.TravelAssistant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NamsMemoryWiringTest {
    private static final String SAVE_ARGUMENTS = "{\"itemType\":\"destination\",\"itemId\":1}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void micronautRetainsTheNativeAdapterDuringTheTurnAndEvictsItAfterward() throws Exception {
        try (var fixture = new Fixture()) {
            var seen = new ArrayList<NamsChatMemory>();
            fixture.respond = (call, body) -> {
                var assistant = fixture.context.getBean(TravelAssistant.class);
                var retained = assertInstanceOf(NamsChatMemory.class,
                    assistant.getChatMemory(FakeNamsServer.ID));
                assertEquals(UUID.fromString(FakeNamsServer.ID), retained.id());
                seen.add(retained);
                if (call == 0) return toolMessage(null);
                assertLiveTools(body, List.of("call_save", "call_read"));
                return finalMessage();
            };
            var response = fixture.chat("Save and read Zermatt");
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(2, seen.size());
            assertSame(seen.getFirst(), seen.getLast());
            fixture.assertCleanMemory();
        }
    }

    @Test
    void realGraphKeepsParallelToolsOnlyForTheActiveTurnAndUsesNativeContextNextTurn() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(1, fixture.context.getBeansOfType(ChatMemoryProvider.class).size());
            assertEquals(0, fixture.context.getBeansOfType(ChatMemoryStore.class).size());
            assertSame(fixture.provider, fixture.context.getBean(ChatMemoryProvider.class));
            fixture.nams.contextMessages.add(Map.of("id", UUID.randomUUID().toString(),
                "role", "user", "content", "An earlier rail journey."));
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Prefers train travel."));
            fixture.nams.reflections.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Plan scenic rail connections."));
            fixture.respond = (call, body) -> {
                assertContext(body, "Prefers train travel.");
                assertTrue(body.toString().contains("An earlier rail journey."));
                assertTrue(body.toString().contains("Plan scenic rail connections."));
                if (call == 0) return toolMessage(null);
                assertLiveTools(body, List.of("call_save", "call_read"));
                return finalMessage();
            };
            var chat = fixture.chat("Save Zermatt to my wishlist");
            assertEquals(200, chat.statusCode(), chat.body());
            assertTrue(chat.body().contains(FakeNamsServer.ID));
            assertEquals(2, fixture.requests.size());
            assertEquals(2, fixture.nams.appends);
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(2, fixture.nams.toolWrites);
            assertRecordedTools(fixture);
            assertEquals(1, fixture.wishlists().findAll(FakeNamsServer.ID).size());
            assertTrue(fixture.send("/api/wishlist?conversationId=" + FakeNamsServer.ID, null).body().contains("Zermatt"));
            var summary = JSON.readTree(fixture.send("/api/conversations", null).body()).get(0);
            for (String field : List.of("conversationId", "title", "preview")) {
                assertTrue(summary.has(field), field);
            }
            assertFalse(summary.hasNonNull("createdAt"));
            assertFalse(summary.hasNonNull("updatedAt"));
            assertEquals(FakeNamsServer.ID, summary.get("conversationId").asText());
            assertEquals("Saved Zermatt.", summary.get("preview").asText());
            var detail = JSON.readTree(fixture.send("/api/conversations/" + FakeNamsServer.ID, null).body());
            assertEquals("Save Zermatt to my wishlist", detail.get("title").asText());
            assertEquals(2, detail.get("messages").size());
            assertEquals("user", detail.get("messages").get(0).get("role").asText());
            assertEquals("Save Zermatt to my wishlist", detail.get("messages").get(0).get("content").asText());
            assertFalse(detail.toString().contains("Prefers train travel."));
            fixture.assertCleanMemory();
            fixture.nams.observations.clear();
            fixture.nams.reflections.clear();
            fixture.nams.contextMessages.clear();
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Zermatt was selected earlier."));
            fixture.nams.reflections.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Continue planning from Zermatt."));
            fixture.respond = (call, body) -> {
                assertNoTools(body);
                assertContext(body, "Zermatt was selected earlier.");
                assertTrue(body.toString().contains("Continue planning from Zermatt."));
                assertFalse(body.toString().contains("Prefers train travel."));
                assertFalse(body.toString().contains("An earlier rail journey."));
                assertEquals(0, fixture.nams.traceReads);
                return finalMessage();
            };
            var followup = fixture.send("/api/chat?q=Continue&conversationId=" + FakeNamsServer.ID, null);
            assertEquals(200, followup.statusCode(), followup.body());
            assertEquals(3, fixture.requests.size());
            fixture.assertCleanMemory();
            try (var connection = JdbcDataSources.raw(fixture.context.getBean(DataSource.class)).getConnection();
                 var rows = connection.createStatement().executeQuery("SELECT count(*) FROM chat_memory")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void mixedTextAndMultipleToolRoundsReachTheModelOnceInOrder() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.respond = (call, body) -> {
                if (call == 0) return toolMessage("I will save Zermatt.");
                assertLiveTools(body, call == 1 ? List.of("call_save", "call_read")
                    : List.of("call_save", "call_read", "call_check"));
                assertEquals(1, countContent(body, "I will save Zermatt."));
                assertEquals(1, countContent(body, "Save and check again"));
                if (call == 1) return "{\"role\":\"assistant\",\"content\":\"Checking once more.\",\"tool_calls\":["
                    + toolCall("call_check", "getWishlist", "{}") + "]}";
                assertEquals(1, countContent(body, "Checking once more."));
                var contents = new ArrayList<String>();
                for (var message : body.get("messages")) contents.add(message.path("content").asText());
                assertTrue(contents.indexOf("I will save Zermatt.") < contents.indexOf("Checking once more."));
                return finalMessage();
            };
            var chat = fixture.chat("Save and check again");
            assertEquals(200, chat.statusCode(), chat.body());
            assertEquals(3, fixture.requests.size());
            assertEquals(4, fixture.nams.appends);
            assertEquals(2, fixture.nams.stepWrites);
            assertEquals(3, fixture.nams.toolWrites);
            assertEquals(fixture.nams.steps.getFirst().get("id"), fixture.nams.toolCalls.get(0).get("stepId"));
            assertEquals(fixture.nams.steps.getFirst().get("id"), fixture.nams.toolCalls.get(1).get("stepId"));
            assertEquals(fixture.nams.steps.getLast().get("id"), fixture.nams.toolCalls.get(2).get("stepId"));
            fixture.assertCleanMemory();
            assertEquals(List.of("Save and check again", "I will save Zermatt.", "Checking once more.", "Saved Zermatt."),
                fixture.nams.messages.stream().map(message -> message.get("content")).toList());
        }
    }

    @Test
    void failedStepStopsToolExecutionWithoutRetryAndReleasesFrameworkMemory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.nams.stepStatus = 503;
            fixture.respond = (call, body) -> call == 0 ? toolMessage(null) : finalMessage();
            var failed = fixture.chat("Save Zermatt");
            assertEquals(502, failed.statusCode(), failed.body());
            assertTrue(failed.body().contains("NAMS operation failed"), failed.body());
            assertEquals(1, fixture.requests.size());
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(0, fixture.nams.toolWrites);
            assertTrue(fixture.wishlists().findAll(FakeNamsServer.ID).isEmpty());
            fixture.nams.stepStatus = 200;
            fixture.assertCleanMemory();
            assertEquals(200, fixture.chat("Continue").statusCode());
            assertNoTools(fixture.requests.getLast());
            assertEquals(1, fixture.nams.stepWrites);
        }
    }

    @Test
    void context500PropagatesThroughTheRealAiService() throws Exception {
        assertContextFailure("status");
    }

    @Test
    void invalidContextJsonPropagatesThroughTheRealAiService() throws Exception {
        assertContextFailure("json");
    }

    @Test
    void invalidContextUuidPropagatesThroughTheRealAiService() throws Exception {
        assertContextFailure("uuid");
    }

    @Test
    void contextTimeoutPropagatesThroughTheRealAiService() throws Exception {
        assertContextFailure("timeout");
    }

    private static void assertContextFailure(String failure) throws Exception {
        try (var fixture = new Fixture()) {
            switch (failure) {
                case "status" -> fixture.nams.contextStatus = 500;
                case "json" -> fixture.nams.contextBodyOverride = "{invalid-json";
                case "uuid" -> fixture.nams.contextBodyOverride = "{\"observations\":[{\"id\":\"invalid-uuid\",\"content\":\"bad\"}]}";
                case "timeout" -> fixture.nams.contextDelay = 1500;
                default -> throw new AssertionError(failure);
            }
            var failed = fixture.chat("Remember my plans");
            assertEquals(failure.equals("timeout") ? 504 : 502, failed.statusCode(), failed.body());
            assertTrue(failed.body().contains(failure.equals("timeout")
                ? "NAMS operation timed out" : "NAMS operation failed"), failed.body());
            assertFalse(failed.body().contains("invalid-uuid"));
            assertEquals(0, fixture.requests.size());
            assertEquals(0, fixture.nams.appends);
            assertEquals(1, fixture.nams.contextReads);
            fixture.nams.contextStatus = 200;
            fixture.nams.contextBodyOverride = null;
            fixture.nams.contextDelay = 0;
            fixture.assertCleanMemory();
            assertEquals(200, fixture.chat("Continue").statusCode());
            assertNoTools(fixture.requests.getLast());
        }
    }

    @Test
    void emptyContextPermitsAnOrdinaryTurnWithoutPolling() throws Exception {
        try (var fixture = new Fixture()) {
            var chat = fixture.chat("Hello");
            assertEquals(200, chat.statusCode(), chat.body());
            assertEquals(1, fixture.requests.size());
            // The AI service reads once before the user append and once for model input.
            assertEquals(2, fixture.nams.contextReads);
            assertEquals(2, fixture.nams.appends);
            assertEquals(0, fixture.nams.stepWrites);
            assertNoTools(fixture.requests.getFirst());
        }
    }

    @Test
    void appendFailurePropagatesWithoutRetryOrModelCall() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.nams.appendStatus = 503;
            var failed = fixture.chat("Hello");
            assertEquals(502, failed.statusCode(), failed.body());
            assertTrue(failed.body().contains("NAMS operation failed"));
            assertEquals(1, fixture.nams.appends);
            assertEquals(0, fixture.requests.size());
            fixture.nams.appendStatus = 200;
            fixture.assertCleanMemory();
        }
    }

    @Test
    void missingConversationAndUpstreamListFailuresKeepTheirHttpPolicy() throws Exception {
        try (var fixture = new Fixture()) {
            var missing = fixture.send("/api/chat?q=Hello&conversationId=" + UUID.randomUUID(), null);
            assertEquals(404, missing.statusCode());
            assertTrue(missing.body().contains("Conversation not found"));
            fixture.nams.status = 503;
            var failed = fixture.send("/api/conversations", null);
            assertEquals(502, failed.statusCode());
            assertTrue(failed.body().contains("NAMS operation failed"));
            assertFalse(failed.body().contains("503"));
            fixture.nams.status = 200;
            fixture.nams.delay = 1500;
            var timeout = fixture.send("/api/conversations", null);
            assertEquals(504, timeout.statusCode());
            assertTrue(timeout.body().contains("NAMS operation timed out"));
        }
    }

    @Test
    void secondParallelResultFailureStopsTheModelAndReleasesFrameworkMemory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.nams.failToolWriteNumber = 2;
            fixture.respond = (call, body) -> call == 0 ? toolMessage(null) : finalMessage();
            var failed = fixture.chat("Save Zermatt and read the wishlist");
            assertEquals(502, failed.statusCode(), failed.body());
            assertTrue(failed.body().contains("NAMS operation failed"), failed.body());
            assertEquals(1, fixture.requests.size());
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(2, fixture.nams.toolWrites);
            assertEquals(1, fixture.nams.toolCalls.size());
            assertEquals(1, fixture.wishlists().findAll(FakeNamsServer.ID).size());
            fixture.assertCleanMemory();
            fixture.nams.failToolWriteNumber = 0;
            assertEquals(200, fixture.chat("Continue").statusCode());
            assertNoTools(fixture.requests.getLast());
            assertEquals(2, fixture.nams.toolWrites);
        }
    }

    @Test
    void committedResultWithLostResponseIsNotRetriedOrReconstructed() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.nams.commitThenFailToolWriteNumber = 2;
            fixture.respond = (call, body) -> call == 0 ? toolMessage(null) : finalMessage();
            var failed = fixture.chat("Save Zermatt and read the wishlist");
            assertEquals(502, failed.statusCode(), failed.body());
            assertTrue(failed.body().contains("NAMS operation failed"), failed.body());
            assertEquals(1, fixture.requests.size());
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(2, fixture.nams.toolWrites);
            assertEquals(2, fixture.nams.toolCalls.size());
            assertEquals(1, fixture.wishlists().findAll(FakeNamsServer.ID).size());
            fixture.assertCleanMemory();
            fixture.nams.commitThenFailToolWriteNumber = 0;
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Zermatt was selected earlier."));
            fixture.respond = (call, body) -> {
                assertNoTools(body);
                assertTrue(body.toString().contains("Zermatt was selected earlier."));
                return finalMessage();
            };
            assertEquals(200, fixture.chat("Continue").statusCode());
            assertEquals(2, fixture.nams.toolWrites);
            assertEquals(1, fixture.nams.stepWrites);
        }
    }

    private static void assertContext(JsonNode body, String expected) {
        var systemTexts = new ArrayList<String>();
        for (var message : body.get("messages")) {
            if ("system".equals(message.path("role").asText())) systemTexts.add(message.get("content").asText());
        }
        assertEquals(1, systemTexts.size());
        assertTrue(systemTexts.getFirst().contains("friendly and knowledgeable Swiss travel advisor"));
        assertTrue(systemTexts.getFirst().contains(expected));
    }

    private static void assertLiveTools(JsonNode body, List<String> expectedIds) {
        var requestedIds = new ArrayList<String>();
        var resultIds = new ArrayList<String>();
        for (var message : body.get("messages")) {
            if (message.has("tool_calls")) {
                for (var call : message.get("tool_calls")) requestedIds.add(call.get("id").asText());
            }
            if ("tool".equals(message.path("role").asText())) resultIds.add(message.get("tool_call_id").asText());
        }
        assertEquals(expectedIds, requestedIds);
        assertEquals(expectedIds.size(), resultIds.size());
        assertTrue(resultIds.containsAll(expectedIds));
    }

    private static long countContent(JsonNode body, String text) {
        long count = 0;
        for (var message : body.get("messages")) {
            if (text.equals(message.path("content").asText())) count++;
        }
        return count;
    }

    private static void assertRecordedTools(Fixture fixture) {
        var step = fixture.nams.steps.getFirst();
        assertEquals(FakeNamsServer.ID, step.get("conversationId"));
        assertEquals("Assistant requested tool execution", step.get("reasoning"));
        assertEquals("Call tools: addToWishlist, getWishlist", step.get("actionTaken"));
        for (var call : fixture.nams.toolCalls) {
            assertEquals(step.get("id"), call.get("stepId"));
            assertEquals("success", call.get("status"));
            String name = call.get("toolName").toString();
            assertEquals(name.equals("addToWishlist") ? SAVE_ARGUMENTS : "{}", call.get("input"));
            if (name.equals("addToWishlist")) assertEquals("Added to wishlist: Zermatt", call.get("output"));
            var result = new ArrayList<String>();
            for (var message : fixture.requests.get(1).get("messages")) {
                if ("tool".equals(message.path("role").asText())
                        && (name.equals("addToWishlist") ? "call_save" : "call_read").equals(message.path("tool_call_id").asText())) {
                    result.add(message.get("content").asText());
                }
            }
            assertEquals(List.of(call.get("output")), result);
            assertFalse(call.containsKey("duration"));
            assertFalse(call.toString().contains("call_save"));
            assertFalse(call.toString().contains("call_read"));
        }
    }

    private static String toolMessage(String text) {
        return "{\"role\":\"assistant\"," + (text == null ? "" : "\"content\":\"" + text + "\",")
            + "\"tool_calls\":[" + toolCall("call_save", "addToWishlist", SAVE_ARGUMENTS)
            + "," + toolCall("call_read", "getWishlist", "{}") + "]}";
    }

    private static String toolCall(String id, String name, String arguments) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
            + "\",\"arguments\":\"" + arguments.replace("\"", "\\\"") + "\"}}";
    }

    private static String finalMessage() {
        return "{\"role\":\"assistant\",\"content\":\"Saved Zermatt.\"}";
    }

    private static void assertNoTools(JsonNode body) {
        for (var message : body.get("messages")) {
            assertFalse("tool".equals(message.path("role").asText()));
            assertFalse(message.has("tool_calls") && !message.get("tool_calls").isEmpty());
        }
        assertFalse(body.toString().contains("call_save"));
        assertFalse(body.toString().contains("call_read"));
    }

    private static final class Fixture implements AutoCloseable {
        final FakeNamsServer nams = new FakeNamsServer();
        final FakeModelServer model;
        final HttpClient client = HttpClient.newHttpClient();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final ApplicationContext context;
        final EmbeddedServer app;
        final NamsChatMemoryProvider provider;
        volatile BiFunction<Integer, JsonNode, String> respond = (call, body) -> finalMessage();

        Fixture() throws Exception {
            model = new FakeModelServer(request -> {
                var body = JSON.readTree(request);
                int call = requests.size();
                requests.add(body);
                return respond.apply(call, body);
            });
            var embeddings = mock(EmbeddingService.class);
            float[] vector = new float[1536];
            vector[0] = 1;
            when(embeddings.generateEmbedding(anyString())).thenReturn(vector);
            context = ApplicationContext.builder().properties(model.applicationProperties(nams.url())).singletons(embeddings).start();
            app = context.getBean(EmbeddedServer.class).start();
            context.getBean(NamsConversationHistory.class).createConversation("Save Zermatt to my wishlist");
            provider = context.getBean(NamsChatMemoryProvider.class);
        }

        WishlistRepository wishlists() {
            return context.getBean(WishlistRepository.class);
        }

        HttpResponse<String> chat(String message) throws Exception {
            return send("/api/chat", JSON.writeValueAsString(Map.of("message", message, "conversationId", FakeNamsServer.ID)));
        }

        HttpResponse<String> send(String path, String body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(app.getURL() + path));
            if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            model.assertHealthy();
            return response;
        }

        void assertCleanMemory() {
            var assistant = context.getBean(TravelAssistant.class);
            assertNull(assistant.getChatMemory(FakeNamsServer.ID));
        }

        @Override
        public void close() {
            context.close();
            client.close();
            nams.close();
            model.close();
            assertEquals(0, nams.traceReads);
            assertEquals(0, nams.deletes);
            assertEquals(1, nams.creates);
        }
    }
}
