package com.example;

import com.example.tools.TravelTools;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingDimensionStartupTest {
    @Test
    void startsWithSchemaSizedEmbeddings() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger wrongDimensions = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/embeddings", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean requested1536 = request.matches("(?s).*\\\"dimensions\\\"\\s*:\\s*1536.*");
            int dimensions = requested1536 ? 1536 : 4096;
            if (!requested1536) {
                wrongDimensions.incrementAndGet();
            }
            requests.incrementAndGet();
            String vector = "0.01" + ",0.0".repeat(dimensions - 1);
            String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                    + "\"embedding\":[" + vector + "]}],\"model\":\"Qwen/Qwen3-Embedding-8B\","
                    + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        provider.start();
        try {
            String baseUrl = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            try (ApplicationContext context = ApplicationContext.builder()
                    .properties(Map.of(
                            "micronaut.server.port", -1,
                            "langchain4j.open-ai.api-key", "test-key",
                            "langchain4j.open-ai.chat-model.base-url", baseUrl,
                            "langchain4j.open-ai.embedding-model.base-url", baseUrl,
                            "langchain4j.open-ai.embedding-model.model-name", "Qwen/Qwen3-Embedding-8B"))
                    .start()) {
                assertEquals(28, requests.get());
                assertEquals(0, wrongDimensions.get());
                context.getBean(TravelTools.class).searchDestinations("mountains");
                assertEquals(29, requests.get());
            }
        } finally {
            provider.stop(0);
        }
    }
}
