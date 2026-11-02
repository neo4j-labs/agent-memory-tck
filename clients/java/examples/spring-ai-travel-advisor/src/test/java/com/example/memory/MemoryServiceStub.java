package com.example.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.ConversationContext;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.NewMessage;

/** Small HTTP fixture for the application's native-client boundary. */
public final class MemoryServiceStub implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Stored> conversations = new LinkedHashMap<>();
    private final Map<UUID, UUID> steps = new LinkedHashMap<>();
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private List<UUID> listOrder;
    private final MemoryClient client;
    private static final Duration WAIT = Duration.ofSeconds(5);

    public MemoryServiceStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) { throw new IllegalStateException(e); }
        server.setExecutor(executor);
        server.createContext("/v1", this::handle);
        server.start();
        client = MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(endpoint().toString()).apiKey("fixture-key").build());
    }
    public URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); }
    public MemoryClient client() { return client; }
    public Conversation seed(String title, List<NewMessage> chronological) {
        Conversation conversation = MemoryClient.await(client.createConversation(new CreateConversation(null,
                title == null ? Map.of() : Map.of("title", title))), WAIT);
        for (NewMessage message : chronological) MemoryClient.await(conversation.addMessage(message), WAIT);
        return MemoryClient.await(client.getConversation(conversation.id()), WAIT);
    }
    public synchronized void setListOrder(List<UUID> ids) { listOrder = List.copyOf(ids); }
    public synchronized void setContext(UUID id, ConversationContext context) { stored(id).context = context; }
    public synchronized List<Message> messages(UUID id) { return List.copyOf(stored(id).messages); }
    public synchronized void delete(UUID id) { conversations.remove(id); }
    public synchronized void failNext(String method, String path, int status, String body) {
        pending.add(new Pending(method, path, status, body, null, null));
    }
    public synchronized void failNextMessage(UUID id, String role, int status, String response) {
        pending.add(new Pending("POST", "/v1/conversations/" + id + "/messages/bulk", status, response, null, role));
    }
    public synchronized DeferredReply deferNext(String method, String path) {
        DeferredReply reply = new DeferredReply();
        pending.add(new Pending(method, path, null, null, reply, null));
        return reply;
    }
    public synchronized List<UUID> stepIds() { return List.copyOf(steps.keySet()); }
    public synchronized List<RecordedRequest> requests() { return List.copyOf(requests); }
    public synchronized void awaitRequestCount(String method, String path, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).count() < expected) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new AssertionError("Request arrival timed out: " + method + " " + path + " expected " + expected);
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
    private Stored stored(UUID id) {
        Stored value = conversations.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown fixture conversation");
        return value;
    }
    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? json.nullNode() : json.readTree(bytes);
        Pending override = null;
        synchronized (this) {
            requests.add(new RecordedRequest(method, path, exchange.getRequestURI().getRawQuery(), body));
            notifyAll();
            for (int i = 0; i < pending.size(); i++) {
                Pending candidate = pending.get(i);
                if (candidate.method.equals(method) && candidate.path.equals(path) && (candidate.role == null || body.path("messages").findValuesAsText("role").contains(candidate.role))) {
                    override = pending.remove(i); break;
                }
            }
        }
        DeferredReply deferred = override == null ? null : override.deferred;
        try {
            if (deferred != null) { deferred.arrival.countDown(); deferred.release.await(); }
            Reply reply;
            synchronized (this) {
                reply = override != null && override.status != null
                        ? new Reply(override.status, override.body) : route(method, path, exchange.getRequestURI().getRawQuery(), body);
            }
            byte[] response = reply.body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status, response.length);
            exchange.getResponseBody().write(response);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); if (deferred != null) deferred.completion.countDown(); }
    }
    private Reply route(String method, String path, String query, JsonNode body) throws IOException {
        if (path.equals("/v1/conversations")) {
            if (method.equals("POST")) {
                Stored value = new Stored(UUID.randomUUID(), body.path("metadata"));
                conversations.put(value.id, value);
                return ok(snapshot(value));
            }
            if (method.equals("GET")) {
                List<UUID> ids = listOrder == null ? conversations.values().stream()
                        .sorted(java.util.Comparator.comparing((Stored value) -> value.createdAt).reversed())
                        .map(value -> value.id).toList() : listOrder;
                List<Map<String, Object>> values = ids.stream().filter(conversations::containsKey)
                        .limit(limit(query, 50)).map(id -> snapshot(conversations.get(id))).toList();
                return ok(Map.of("conversations", values));
            }
        }
        if (path.startsWith("/v1/conversations/")) {
            String[] parts = path.substring("/v1/conversations/".length()).split("/");
            UUID id = UUID.fromString(parts[0]);
            Stored value = conversations.get(id);
            if (value == null) return missing();
            if (parts.length == 1 && method.equals("GET")) return ok(snapshot(value));
            if (parts.length == 1 && method.equals("DELETE")) { delete(id); return ok(Map.of()); }
            if (parts.length == 3 && parts[1].equals("messages") && parts[2].equals("bulk") && method.equals("POST")) {
                List<Message> added = new ArrayList<>();
                for (JsonNode item : body.path("messages")) {
                    added.add(new Message(UUID.randomUUID(), MessageRole.valueOf(item.path("role").asText().toUpperCase(Locale.ROOT)), item.path("content").asText()));
                }
                value.messages.addAll(added); value.updatedAt = Instant.now();
                return ok(Map.of("messages", added.stream().map(this::wire).toList()));
            }
            if (parts.length == 2 && parts[1].equals("messages")) {
                if (method.equals("POST")) {
                    Message message = new Message(UUID.randomUUID(), MessageRole.valueOf(body.path("role").asText().toUpperCase(Locale.ROOT)), body.path("content").asText());
                    value.messages.add(message); value.updatedAt = Instant.now();
                    return ok(wire(message));
                }
                if (method.equals("GET")) {
                    List<Message> newest = new ArrayList<>(value.messages); java.util.Collections.reverse(newest);
                    return ok(Map.of("messages", newest.stream().limit(limit(query, 50)).map(this::wire).toList()));
                }
            }
            if (parts.length == 2 && parts[1].equals("context") && method.equals("GET")) {
                ConversationContext context = value.context;
                if (context == null) return ok(Map.of("reflections", List.of(), "observations", List.of(), "recentMessages", value.messages.reversed().stream().map(this::wire).toList()));
                return ok(Map.of("reflections", context.reflections(), "observations", context.observations(), "recentMessages", context.recentMessages().stream().map(this::wire).toList()));
            }
        }
        if (path.equals("/v1/reasoning/steps") && method.equals("POST")) {
            UUID conversationId = UUID.fromString(body.path("conversationId").asText());
            if (!conversations.containsKey(conversationId)) return missing();
            UUID id = UUID.randomUUID(); steps.put(id, conversationId);
            var response = json.createObjectNode(); response.setAll((com.fasterxml.jackson.databind.node.ObjectNode) body); response.put("id", id.toString());
            return ok(response);
        }
        if (path.equals("/v1/reasoning/tool-calls") && method.equals("POST")) {
            UUID stepId = UUID.fromString(body.path("stepId").asText());
            if (!steps.containsKey(stepId) || !conversations.containsKey(steps.get(stepId))) return missing();
            var response = json.createObjectNode(); response.setAll((com.fasterxml.jackson.databind.node.ObjectNode) body); response.put("id", UUID.randomUUID().toString());
            return ok(response);
        }
        return missing();
    }
    private int limit(String query, int fallback) {
        if (query == null) return fallback;
        for (String item : query.split("&")) if (item.startsWith("limit=")) return Integer.parseInt(item.substring(6));
        return fallback;
    }
    private Map<String, Object> snapshot(Stored value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id.toString()); result.put("metadata", value.metadata);
        result.put("createdAt", value.createdAt.toString()); result.put("updatedAt", value.updatedAt.toString());
        result.put("messageCount", value.messages.size());
        if (!value.messages.isEmpty()) result.put("firstMessageSnippet", value.messages.getFirst().content());
        return result;
    }
    private Map<String, Object> wire(Message message) {
        return Map.of("id", message.id().toString(), "role", message.role().name().toLowerCase(Locale.ROOT), "content", message.content());
    }
    private Reply ok(Object value) throws IOException { return new Reply(200, json.writeValueAsString(value)); }
    private Reply missing() { return new Reply(404, "{\"error\":\"not found\"}"); }
    private static final class Stored {
        final UUID id; final JsonNode metadata; final Instant createdAt = Instant.now();
        Instant updatedAt = createdAt; final List<Message> messages = new ArrayList<>(); ConversationContext context;
        Stored(UUID id, JsonNode metadata) { this.id = id; this.metadata = metadata; }
    }
    private record Reply(int status, String body) {}
    private record Pending(String method, String path, Integer status, String body, DeferredReply deferred, String role) {}
    public record RecordedRequest(String method, String path, String query, JsonNode body) {}
    public static final class DeferredReply {
        private final CountDownLatch arrival = new CountDownLatch(1), release = new CountDownLatch(1), completion = new CountDownLatch(1);
        public void awaitArrival(Duration timeout) { await(arrival, timeout); }
        public void release() { release.countDown(); }
        public void awaitCompletion(Duration timeout) { await(completion, timeout); }
        private void await(CountDownLatch latch, Duration timeout) {
            try { if (!latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) throw new AssertionError("Deferred fixture wait timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
}
