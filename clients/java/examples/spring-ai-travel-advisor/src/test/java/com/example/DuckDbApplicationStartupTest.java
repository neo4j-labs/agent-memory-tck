package com.example;

import com.example.memory.MemoryServiceStub;
import com.example.memory.NamsConversationHistory;
import com.example.service.TravelAssistant;
import com.example.service.SpringAiConversationTest.Provider;
import java.util.UUID;
import com.example.model.WishlistItem;
import com.example.repository.ActivityRepository;
import com.example.repository.DestinationRepository;
import com.example.repository.HotelRepository;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import com.example.tools.TravelTools;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DuckDbApplicationStartupTest {
    public static ServletWebServerApplicationContext start(int port, EmbeddingService embeddings, URI memoryEndpoint, Object... extra) {
        return (ServletWebServerApplicationContext) application(embeddings, memoryEndpoint, extra).run(
            "--server.port=" + port, "--spring.ai.openai.api-key=startup-test",
            "--spring.ai.openai.base-url=http://127.0.0.1:1/v1");
    }

    public static SpringApplication application(EmbeddingService embeddings, URI memoryEndpoint, Object... extra) {
        var app = new SpringApplication(Application.class);
        app.addInitializers(context -> {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-memory-fixture",
                    Map.of("app.memory.endpoint", memoryEndpoint.toString(), "app.memory.api-key", "test-key")));
            context.addBeanFactoryPostProcessor(factory -> {
                var registry = (BeanDefinitionRegistry) factory;
                registry.removeBeanDefinition("embeddingService");
                factory.registerSingleton("embeddingService", embeddings);
                for (Object replacement : extra) {
                    if (replacement instanceof TravelAssistant) {
                        registry.removeBeanDefinition("swissTravelAssistant");
                        factory.registerSingleton("swissTravelAssistant", replacement);
                    } else if (replacement instanceof ChatModel) {
                        registry.removeBeanDefinition("openAiChatModel");
                        factory.registerSingleton("openAiChatModel", replacement);
                    } else throw new IllegalArgumentException("Unsupported test bean replacement");
                }
            });
        });
        return app;
    }
    @Test
    void embeddingFailureFailsApplicationStartupWithItsStageAndCause() throws Exception {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.generateEmbedding(anyString()))
                .thenThrow(new IllegalStateException("Embedding provider unavailable"));
        Exception failure;
        try (var memory = new MemoryServiceStub()) {
            failure = assertThrows(Exception.class, () -> start(0, embeddings, memory.endpoint()));
        }
        Throwable cause = failure;
        while (cause != null && !String.valueOf(cause.getMessage()).contains("destination embedding backfill")) cause = cause.getCause();
        assertNotNull(cause);
        assertEquals("Embedding provider unavailable", cause.getCause().getMessage());
    }

    @Test
    void blocksHttpUntilCatalogEmbeddingsAndIndexesAreReady() throws Exception {
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        float[] vector = new float[1536];
        vector[0] = 1;
        when(embeddings.generateEmbedding(anyString())).thenAnswer(invocation -> {
            embeddingStarted.countDown();
            assertTrue(releaseEmbedding.await(30, TimeUnit.SECONDS), "Embedding was never released");
            return vector;
        });
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try (var memory = new MemoryServiceStub();
             var executor = Executors.newSingleThreadExecutor();
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var startup = executor.submit(() -> start(port, embeddings, memory.endpoint()));
            try {
                assertTrue(embeddingStarted.await(30, TimeUnit.SECONDS), "Startup did not reach embedding generation");
                assertFalse(startup.isDone());
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/conversations"))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                assertThrows(ConnectException.class,
                        () -> client.send(request, HttpResponse.BodyHandlers.ofString()),
                        "HTTP must not accept requests while catalog initialization is incomplete");
            } finally {
                releaseEmbedding.countDown();
            }
            try (var context = startup.get(30, TimeUnit.SECONDS)) {
            var server = context;
            verify(embeddings, times(28)).generateEmbedding(anyString());
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                assertEquals(3, scalar(connection, "SELECT count(*) FROM duckdb_indexes() WHERE sql LIKE '%HNSW%'"));
            }
            assertEquals("[]", get(client, server, "/api/conversations"));
            assertFalse(context.getBean(TravelTools.class).searchDestinations("mountains").isBlank());
            }
        }
    }

    @Test
    void startsWithCompleteCatalogAndRetainsRemoteHistoryWhileResettingWishlist() throws Exception {
        try (var fixture = new MemoryServiceStub()) {
            var conversation = fixture.seed("Visit Zurich", List.of());
            String conversationId = conversation.id().toString();
            for (int start = 0; start < 2; start++) {
                EmbeddingService embeddings = mock(EmbeddingService.class);
                float[] vector = new float[1536];
                vector[0] = 1;
                when(embeddings.generateEmbedding(anyString())).thenReturn(vector);
                try (var context = start(0, embeddings, fixture.endpoint())) {
                    var server = context;
                    DataSource source = context.getBean(DataSource.class);
                    try (Connection first = source.getConnection(); Connection second = source.getConnection()) {
                        assertEquals(7, scalar(first, "SELECT count(*) FROM destinations"));
                        assertEquals(12, scalar(first, "SELECT count(*) FROM hotels"));
                        assertEquals(9, scalar(first, "SELECT count(*) FROM activities"));
                        assertEquals(3, scalar(first, "SELECT count(*) FROM duckdb_indexes() WHERE sql LIKE '%HNSW%'"));
                        String distance = "SELECT ST_Distance_Spheroid(ST_Point2D(8.0,47.0),ST_Point2D(9.0,47.0))";
                        assertEquals(scalar(first, distance), scalar(second, distance), 0.01);
                        assertEquals(76056, scalar(second, distance), 100);
                    }
                    verify(embeddings, times(28)).generateEmbedding(anyString());
                    assertTrue(context.getBean(DestinationRepository.class).findById(1L).isPresent());
                    assertTrue(context.getBean(HotelRepository.class).findById(1L).isPresent());
                    assertTrue(context.getBean(ActivityRepository.class).findById(1L).isPresent());
                    var memory = context.getBean(NamsConversationHistory.class);
                    assertEquals(start == 0 ? 0 : 20, memory.detail(conversation.id()).messages().size());
                    assertTrue(context.getBean(WishlistRepository.class).findAll(conversationId).isEmpty());
                    var tools = context.getBean(TravelTools.class);
                    assertFalse(tools.searchDestinations("mountains").isBlank());
                    assertFalse(tools.searchHotels("hotel", null, null).isBlank());
                    assertFalse(tools.searchActivities("hike", null).isBlank());
                    assertFalse(tools.searchNearbyDestinations("lake", "Zurich", 100.0).isBlank());
                    MemoryClient.await(conversation.addMessage(NewMessage.user("Visit Zurich")), Duration.ofSeconds(5));
                    tools.addToWishlist("destination", 1L, new ToolContext(Map.of("conversationId", conversationId, "userMessage", "Save Zermatt")));
                    assertEquals(1, context.getBean(WishlistRepository.class).findAll(conversationId).size());
                    for (int i = 0; i < 25; i++) MemoryClient.await(conversation.addMessage(NewMessage.user("Turn " + i)), Duration.ofSeconds(5));
                    assertEquals(20, memory.detail(conversation.id()).messages().size());
                    try (HttpClient client = HttpClient.newHttpClient()) {
                        assertTrue(get(client, server, "/api/conversations").contains(conversationId));
                        assertTrue(get(client, server, "/api/conversations/" + conversationId).contains("Turn 24"));
                        assertTrue(get(client, server, "/api/wishlist?conversationId=" + conversationId).contains("Zermatt"));
                        assertTrue(get(client, server, "/").contains("<title>Swiss Travel Advisor</title>"));
                    }
                }
            }
        }
    }

    @Test
    void restoresRemoteHistoryAcrossJvmRestartWhileWishlistResets() throws Exception {
        var exports = new AtomicInteger();
        var collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exports.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        collector.start();
        String endpoint = "http://127.0.0.1:" + collector.getAddress().getPort();
        try (var memory = new MemoryServiceStub(); var provider = new Provider()) {
            provider.tools = false;
            var conversation = memory.seed("Cross-process preference", List.of());
            for (boolean enabled : new boolean[]{false, true}) {
                var output = Files.createTempFile("spring-startup-process-", ".log");
                var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("java.class.path"), ProcessProbe.class.getName(), memory.endpoint().toString(), conversation.id().toString(),
                    enabled ? "read" : "write", provider.endpoint().toString());
                builder.environment().remove("OTEL_TRACES_EXPORTER");
                builder.environment().remove("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
                builder.environment().remove("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL");
                if (enabled) builder.environment().put("OTEL_TRACES_EXPORTER", "otlp");
                builder.environment().put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
                builder.environment().put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
                builder.environment().put("OTEL_METRICS_EXPORTER", "none");
                builder.environment().put("OTEL_LOGS_EXPORTER", "none");
                builder.redirectErrorStream(true).redirectOutput(output.toFile());
                Process child = builder.start();
                try {
                    assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Child startup timed out");
                    String log = Files.readString(output);
                    assertEquals(0, child.exitValue(), log);
                    assertTrue(log.contains("PROCESS_PROBE_OK"), log);
                    if (!enabled) {
                        assertEquals(0, exports.get());
                        assertEquals(List.of("Remember Zermatt across processes", "I checked your request."),
                                memory.messages(conversation.id()).stream().map(message -> message.content()).toList());
                        assertEquals(1, provider.requests.size());
                    } else {
                        assertTrue(log.contains("REMOTE_HISTORY_OK"), log);
                        assertTrue(log.contains("WISHLIST_EMPTY"), log);
                        assertEquals(2, provider.requests.size());
                        assertTrue(provider.requests.getLast().toString().contains("Remember Zermatt across processes"));
                    }
                } finally {
                    child.destroyForcibly();
                    Files.deleteIfExists(output);
                }
            }
            assertTrue(exports.get() > 0, "OTEL_TRACES_EXPORTER=otlp must enable real export");
        } finally { collector.stop(0); }
    }

    public static class ProcessProbe {
        public static void main(String[] args) throws Exception {
            var count = new AtomicInteger();
            var embeddings = new EmbeddingService(null) {
                @Override public float[] generateEmbedding(String text) {
                    count.incrementAndGet();
                    float[] vector = new float[1536]; vector[0] = 1; return vector;
                }
            };
            try (var context = application(embeddings, URI.create(args[0])).run("--server.port=0",
                    "--spring.ai.openai.api-key=test-key", "--spring.ai.openai.base-url=" + args[3],
                    "--management.tracing.sampling.probability=1")) {
                assertEquals(28, count.get());
                var memory = context.getBean(NamsConversationHistory.class);
                var conversation = memory.require(UUID.fromString(args[1]));
                String id = conversation.id().toString();
                var wishlist = context.getBean(WishlistRepository.class);
                assertTrue(wishlist.findAll(id).isEmpty());
                if (args[2].equals("write")) {
                    context.getBean(TravelAssistant.class).chat(conversation, "Remember Zermatt across processes");
                    assertTrue(wishlist.save(id, new WishlistItem("destination", 1L)));
                    assertEquals(1, wishlist.findAll(id).size());
                } else if (args[2].equals("read")) {
                    assertTrue(memory.detail(conversation.id()).messages().stream()
                            .anyMatch(message -> message.content().equals("Remember Zermatt across processes")));
                    System.out.println("REMOTE_HISTORY_OK");
                    System.out.println("WISHLIST_EMPTY");
                    context.getBean(TravelAssistant.class).chat(conversation, "What preference did I ask you to remember?");
                } else throw new IllegalArgumentException("Unknown process mode");
                boolean enabled = "otlp".equals(System.getenv("OTEL_TRACES_EXPORTER"));
                assertEquals(enabled, context.getEnvironment().getProperty("management.tracing.export.otlp.enabled", Boolean.class));
                assertEquals("HTTP", context.getEnvironment().getProperty("management.opentelemetry.tracing.export.otlp.transport"));
                context.getBean(OpenTelemetry.class).getTracer("startup-test")
                    .spanBuilder("environment-alias-check").startSpan().end();
                System.out.println("PROCESS_PROBE_OK");
            }
        }
    }

    private static String get(HttpClient client, ServletWebServerApplicationContext server, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getWebServer().getPort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static double scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getDouble(1);
        }
    }
}
