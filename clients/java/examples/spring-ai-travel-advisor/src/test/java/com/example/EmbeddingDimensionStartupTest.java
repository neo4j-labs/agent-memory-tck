package com.example;

import com.example.tools.TravelTools;
import com.example.memory.MemoryServiceStub;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddingDimensionStartupTest {
    @Test
    void startsWithSchemaSizedEmbeddingsAndNoTelemetryCollector() throws Exception {
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
        try (var memory = new MemoryServiceStub()) {
            String baseUrl = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            try (var context = SpringApplication.run(Application.class,
                    "--server.port=0", "--app.memory.endpoint=" + memory.endpoint(), "--app.memory.api-key=test-key", "--spring.ai.openai.api-key=test-key",
                    "--spring.ai.openai.base-url=" + baseUrl,
                    "--spring.ai.openai.embedding.model=Qwen/Qwen3-Embedding-8B")) {
                assertEquals(28, requests.get());
                assertEquals(0, wrongDimensions.get());
                assertFalse(context.getEnvironment().getProperty("management.tracing.export.otlp.enabled", Boolean.class));
                context.getBean(TravelTools.class).searchDestinations("mountains");
                assertEquals(29, requests.get());
            }
        } finally {
            provider.stop(0);
        }
    }
}
