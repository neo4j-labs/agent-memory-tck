package com.example.controller;

import com.example.DuckDbApplicationStartupTest;
import com.example.memory.MemoryServiceStub;
import com.example.memory.NamsConversationHistory;
import com.example.service.SpringAiConversationTest;
import com.example.service.SpringAiConversationTest.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerConversationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void preservesHttpIdentityAndFields() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider();
             var context = SpringAiConversationTest.boot(memory, provider); var client = HttpClient.newHttpClient()) {
            provider.tools = false; int port = context.getWebServer().getPort();
            int before = memory.requests().size();
            assertEquals(400, send(client, port, "/api/chat", "{\"message\":\" \"}").statusCode());
            assertEquals(400, send(client, port, "/api/chat", "{\"message\":\"hi\",\"conversationId\":\"1-1-1-1-1\"}").statusCode());
            assertEquals(400, send(client, port, "/api/conversations/invalid", null).statusCode());
            assertEquals(0, memory.requests().size() - before);
            assertEquals(404, send(client, port, "/api/chat", chat("hi", UUID.randomUUID())).statusCode());
            assertEquals(0, memory.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals("/v1/conversations")).count());
            var response = send(client, port, "/api/chat", "{\"message\":\"first user text\"}");
            assertEquals(200, response.statusCode()); JsonNode json = mapper.readTree(response.body());
            String id = json.path("conversationId").asText();
            assertEquals(UUID.fromString(id).toString(), id);
            assertTrue(memory.requests().stream().anyMatch(r -> r.path().equals("/v1/conversations/" + id + "/context")));
            assertFalse(memory.requests().stream().filter(r -> r.path().equals("/v1/conversations") && r.method().equals("POST")).findFirst().orElseThrow().body().has("id"));
            assertEquals(id, context.getBean(NamsConversationHistory.class).list().getFirst().conversationId());
            assertEquals("I checked your request.", json.path("message").asText()); assertEquals(2, json.size());
            JsonNode summary = mapper.readTree(send(client, port, "/api/conversations", null).body()).get(0);
            assertEquals("first user text", summary.path("preview").asText());
            assertEquals("first user text", summary.path("title").asText());
            assertTrue(summary.hasNonNull("createdAt")); assertTrue(summary.hasNonNull("updatedAt"));
            JsonNode detail = mapper.readTree(send(client, port, "/api/conversations/" + id, null).body());
            assertEquals(List.of("user", "assistant"), detail.path("messages").findValuesAsText("role"));
            assertEquals(200, send(client, port, "/api/chat?q=hello&conversationId=" + id.toUpperCase(), null).statusCode());
            assertEquals("[]", send(client, port, "/api/wishlist", null).body());
            var page = send(client, port, "/", null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Conversation history is saved across app restarts"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"lookup", "context", "user", "assistant"})
    void requiredMemoryFailuresStopAtTheCorrectBoundary(String operation) throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider();
             var context = SpringAiConversationTest.boot(memory, provider); var client = HttpClient.newHttpClient()) {
            provider.tools = false; var conversation = memory.seed("boundary", List.of());
            int before = memory.requests().size(); String base = "/v1/conversations/" + conversation.id();
            if (operation.equals("lookup")) memory.failNext("GET", base, 503, "{\"error\":\"test-key secret-body\"}");
            else if (operation.equals("context")) memory.failNext("GET", base + "/context", 503, "{\"error\":\"test-key secret-body\"}");
            else memory.failNextMessage(conversation.id(), operation, 503, "{\"error\":\"test-key secret-body\"}");
            var response = send(client, context.getWebServer().getPort(), "/api/chat", chat("Boundary request", conversation.id()));
            assertSafe(response, 503, "Memory service unavailable");
            assertEquals(operation.equals("assistant") ? 1 : 0, provider.requests.size());
            var writes = memory.requests().subList(before, memory.requests().size()).stream().filter(r -> r.method().equals("POST") && r.path().endsWith("/messages/bulk")).toList();
            assertEquals(operation.equals("assistant") ? List.of("user", "assistant") : operation.equals("user") ? List.of("user") : List.of(), writes.stream().flatMap(r -> r.body().path("messages").findValuesAsText("role").stream()).toList());
            assertEquals(operation.equals("assistant") ? 1 : 0, memory.messages(conversation.id()).size());
        }
    }
    @Test void mapsScoped404WithoutLeakingUpstreamDetails() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider();
             var context = SpringAiConversationTest.boot(memory, provider); var client = HttpClient.newHttpClient()) {
            provider.tools = false; int port = context.getWebServer().getPort();
            for (String operation : List.of("getConversation", "messages", "context", "addMessages", "list", "create")) {
                for (int status : new int[]{404, 401, 403, 429, 500, 503}) {
                    var conversation = memory.seed("errors", List.of()); String base = "/v1/conversations/" + conversation.id();
                    String method = operation.equals("create") || operation.equals("addMessages") ? "POST" : "GET";
                    String path = switch (operation) { case "list", "create" -> "/v1/conversations"; case "messages", "context" -> base + "/" + operation; case "addMessages" -> base + "/messages/bulk"; default -> base; };
                    memory.failNext(method, path, status, "{\"error\":\"test-key secret-body\"}");
                    String api = operation.equals("list") ? "/api/conversations" : operation.equals("messages") || operation.equals("getConversation") ? "/api/conversations/" + conversation.id() : "/api/chat";
                    String body = api.equals("/api/chat") ? operation.equals("create") ? "{\"message\":\"hello\"}" : chat("hello", conversation.id()) : null;
                    boolean scoped = status == 404 && !operation.equals("list") && !operation.equals("create");
                    assertSafe(send(client, port, api, body), scoped ? 404 : 503, scoped ? "Conversation not found" : "Memory service unavailable");
                }
            }
            for (String operation : List.of("lookup", "messages", "context", "create", "list")) {
                var conversation = memory.seed("decode", List.of()); String base = "/v1/conversations/" + conversation.id();
                String path = switch (operation) { case "create", "list" -> "/v1/conversations"; case "messages", "context" -> base + "/" + operation; default -> base; };
                memory.failNext(operation.equals("create") ? "POST" : "GET", path, 200, operation.equals("context") ? "{\"recentMessages\":[{\"id\":\"broken\",\"role\":\"user\",\"content\":\"test-key secret-body\"}]}" : "{\"id\":\"broken\"}");
                String api = operation.equals("list") ? "/api/conversations" : operation.equals("create") || operation.equals("context") ? "/api/chat" : "/api/conversations/" + conversation.id();
                assertSafe(send(client, port, api, api.equals("/api/chat") ? operation.equals("create") ? "{\"message\":\"hello\"}" : chat("hello", conversation.id()) : null), 503, "Memory service unavailable");
            }
        }
    }
    @Test void mapsNativeTimeoutToSafe503() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider();
             var context = (ServletWebServerApplicationContext) DuckDbApplicationStartupTest.application(SpringAiConversationTest.embeddings(), memory.endpoint()).run("--server.port=0", "--spring.ai.openai.api-key=test-key", "--spring.ai.openai.base-url=" + provider.endpoint(), "--app.memory.timeout=100ms"); var client = HttpClient.newHttpClient()) {
            var conversation = memory.seed("timeout", List.of());
            var deferred = memory.deferNext("GET", "/v1/conversations/" + conversation.id() + "/context");
            try { assertSafe(send(client, context.getWebServer().getPort(), "/api/chat", chat("timeout", conversation.id())), 503, "Memory service unavailable"); assertTrue(provider.requests.isEmpty()); }
            finally { deferred.release(); deferred.awaitCompletion(Duration.ofSeconds(5)); }
        }
    }
    @Test void invalidAcknowledgementFailsWithoutRetry() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider();
             var context = SpringAiConversationTest.boot(memory, provider); var client = HttpClient.newHttpClient()) {
            provider.tools = false;
            for (String role : List.of("user", "assistant")) {
                for (String fault : List.of("missing", "role", "content")) {
                    var conversation = memory.seed("ack", List.of()); int before = memory.requests().size(); int models = provider.requests.size();
                    String expected = role.equals("user") ? "hello" : "I checked your request.";
                    String ack = "{" + (fault.equals("missing") ? "" : "\"id\":\"" + UUID.randomUUID() + "\",") + "\"role\":\"" + (fault.equals("role") ? role.equals("user") ? "assistant" : "user" : role) + "\",\"content\":\"" + (fault.equals("content") ? "wrong" : expected) + "\"}";
                    memory.failNextMessage(conversation.id(), role, 200, "{\"messages\":[" + ack + "]}");
                    assertSafe(send(client, context.getWebServer().getPort(), "/api/chat", chat("hello", conversation.id())), 503, "Memory service unavailable");
                    var requests = memory.requests().subList(before, memory.requests().size());
                    assertEquals(1, requests.stream().filter(r -> r.method().equals("POST") && r.body().path("messages").findValuesAsText("role").contains(role)).count());
                    assertEquals(role.equals("user") ? 0 : 1, provider.requests.size() - models);
                    assertEquals(role.equals("user") ? 0 : 1, memory.messages(conversation.id()).size());
                    assertEquals("/v1/conversations/" + conversation.id() + "/messages/bulk", requests.getLast().path());
                }
            }
        }
    }
    @Test void sameConversationHttpTurnsCanOverlap() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider();
             var context = SpringAiConversationTest.boot(memory, provider); var client = HttpClient.newHttpClient(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            provider.tools = false; provider.entered = new CountDownLatch(2); provider.release = new CountDownLatch(1);
            var conversation = memory.seed("concurrent", List.of()); int port = context.getWebServer().getPort(); int before = memory.requests().size();
            var first = executor.submit(() -> send(client, port, "/api/chat", chat("first", conversation.id())));
            var second = executor.submit(() -> send(client, port, "/api/chat", chat("second", conversation.id())));
            try {
                assertTrue(provider.entered.await(10, TimeUnit.SECONDS), "Both turns must reach the model before either is released");
                assertEquals(2, provider.requests.size());
                assertFalse(first.isDone()); assertFalse(second.isDone());
                assertEquals(List.of("user", "user"), memory.messages(conversation.id()).stream().map(m -> m.role().name().toLowerCase()).toList());
            } finally {
                provider.release.countDown();
            }
            assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode()); assertEquals(200, second.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(List.of("user", "user", "assistant", "assistant"), memory.requests().subList(before, memory.requests().size()).stream().filter(r -> r.method().equals("POST") && r.path().endsWith("messages/bulk")).flatMap(r -> r.body().path("messages").findValuesAsText("role").stream()).toList());
        }
    }
    private void assertSafe(HttpResponse<String> response, int status, String message) throws Exception {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(mapper.readTree("{\"error\":\"" + message + "\"}"), mapper.readTree(response.body()));
        assertFalse(response.body().contains("test-key")); assertFalse(response.body().contains("secret-body"));
    }
    private static String chat(String message, UUID id) { return "{\"message\":\"" + message + "\",\"conversationId\":\"" + id + "\"}"; }
    private static HttpResponse<String> send(HttpClient client, int port, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
