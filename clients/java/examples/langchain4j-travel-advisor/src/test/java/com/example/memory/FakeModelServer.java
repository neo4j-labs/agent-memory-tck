package com.example.memory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertNull;

/** OpenAI-compatible transport for NAMS application tests; scenarios own their chat responses. */
final class FakeModelServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

    @FunctionalInterface
    interface ChatResponder {
        String respond(String request) throws IOException;
    }

    FakeModelServer(ChatResponder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String message = responder.respond(request);
                String finish = message.contains("tool_calls") ? "tool_calls" : "stop";
                respond(exchange, 200, "{\"id\":\"response\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\","
                    + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finish
                    + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
            } catch (Throwable failure) {
                handlerFailure.compareAndSet(null, failure);
                respond(exchange, 500, "{\"error\":{\"message\":\"Model request assertion failed\"}}");
            }
        });
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String values = "1," + "0,".repeat(1534) + "0";
            respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":["
                + values + "]}],\"model\":\"test\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}");
        });
        server.start();
    }

    Map<String, Object> applicationProperties(String namsUrl) {
        String modelUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        return Map.of(
            "nams.base-url", namsUrl, "nams.api-key", "test-key", "nams.wait-timeout", "1s",
            "micronaut.server.port", -1, "langchain4j.open-ai.api-key", "test-key",
            "langchain4j.open-ai.embedding-model.base-url", modelUrl,
            "langchain4j.open-ai.chat-model.base-url", modelUrl);
    }

    void assertHealthy() {
        assertNull(handlerFailure.get(), () -> "Model handler failed: " + handlerFailure.get());
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        assertHealthy();
    }
}
