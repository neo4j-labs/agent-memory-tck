package com.example;

import com.example.memory.NamsConversationHistory;
import com.example.memory.FakeNamsServer;
import com.example.repository.ActivityRepository;
import com.example.repository.DestinationRepository;
import com.example.repository.DuckDbExtensionManager;
import com.example.repository.HotelRepository;
import com.example.repository.JdbcDataSources;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import com.example.tools.TravelTools;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import java.net.URI;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuckDbApplicationStartupTest {
    @Test
    void embeddingFailureFailsApplicationStartupWithItsStageAndCause() throws Exception {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.generateEmbedding(anyString()))
                .thenThrow(new IllegalStateException("Embedding provider unavailable"));
        try (FakeNamsServer nams = new FakeNamsServer();
             ApplicationContext context = ApplicationContext.builder()
                .properties(Map.of("nams.base-url", nams.url(), "nams.api-key", "test-key",
                        "micronaut.server.port", -1,
                        "langchain4j.open-ai.api-key", "startup-test"))
                .singletons(embeddings).build()) {
            try {
                IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
                assertTrue(failure.getMessage().contains("destination embedding backfill"));
                assertEquals("Embedding provider unavailable", failure.getCause().getMessage());
            } finally {
                // A failed context is not running, so close() does not destroy its beans.
                context.getBean(DuckDbExtensionManager.class).close();
            }
        }
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
        try (FakeNamsServer nams = new FakeNamsServer();
             ApplicationContext context = ApplicationContext.builder()
                .properties(Map.of("nams.base-url", nams.url(), "nams.api-key", "test-key",
                        "micronaut.server.port", port,
                        "langchain4j.open-ai.api-key", "startup-test"))
                .singletons(embeddings).build();
             var executor = Executors.newSingleThreadExecutor();
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var startup = executor.submit(() -> {
                context.start();
                return context.getBean(EmbeddedServer.class).start();
            });
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
            EmbeddedServer server = startup.get(30, TimeUnit.SECONDS);
            verify(embeddings, times(28)).generateEmbedding(anyString());
            try (Connection connection = JdbcDataSources.raw(context.getBean(DataSource.class)).getConnection()) {
                assertEquals(3, scalar(connection, "SELECT count(*) FROM duckdb_indexes() WHERE sql LIKE '%HNSW%'"));
            }
            assertEquals("[]", get(client, server, "/api/conversations"));
            assertFalse(context.getBean(TravelTools.class).searchDestinations("mountains").isBlank());
        }
    }

    @Test
    void startsWithCompleteCatalogAndResetsStateOnRestart() throws Exception {
        String conversationId = FakeNamsServer.ID;
        for (int start = 0; start < 2; start++) {
            EmbeddingService embeddings = mock(EmbeddingService.class);
            float[] vector = new float[1536];
            vector[0] = 1;
            when(embeddings.generateEmbedding(anyString())).thenReturn(vector);
            try (FakeNamsServer nams = new FakeNamsServer();
             ApplicationContext context = ApplicationContext.builder()
                    .properties(Map.of("nams.base-url", nams.url(), "nams.api-key", "test-key",
                        "micronaut.server.port", -1,
                            "langchain4j.open-ai.api-key", "startup-test"))
                    .singletons(embeddings).start()) {
                EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
                DataSource source = JdbcDataSources.raw(context.getBean(DataSource.class));
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
                var memory = context.getBean(ChatMemoryProvider.class);
                assertEquals(conversationId, context.getBean(NamsConversationHistory.class).createConversation("Visit Zurich"));
                assertTrue(memory.get(conversationId).messages().isEmpty());
                assertTrue(context.getBean(WishlistRepository.class).findAll(conversationId).isEmpty());
                var tools = context.getBean(TravelTools.class);
                assertFalse(tools.searchDestinations("mountains").isBlank());
                assertFalse(tools.searchHotels("hotel", null, null).isBlank());
                assertFalse(tools.searchActivities("hike", null).isBlank());
                assertFalse(tools.searchNearbyDestinations("lake", "Zurich", 100.0).isBlank());
                context.getBean(ChatMemoryProvider.class).get(conversationId).add(UserMessage.from("Visit Zurich"));
                tools.addToWishlist(conversationId, "destination", 1L);
                assertEquals(1, context.getBean(WishlistRepository.class).findAll(conversationId).size());
                var chatMemory = context.getBean(ChatMemoryProvider.class).get(conversationId);
                for (int i = 0; i < 25; i++) chatMemory.add(UserMessage.from("Turn " + i));
                var recalled = memory.get(conversationId).messages();
                assertEquals(1, recalled.size());
                String prompt = assertInstanceOf(SystemMessage.class, recalled.getFirst()).text();
                assertTrue(prompt.contains("Turn 24"));
                assertFalse(prompt.contains("Visit Zurich"));
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertTrue(get(client, server, "/api/conversations").contains(conversationId));
                    assertTrue(get(client, server, "/api/conversations/" + conversationId).contains("Turn 24"));
                    assertTrue(get(client, server, "/api/wishlist?conversationId=" + conversationId).contains("Zermatt"));
                }
            }
        }
    }

    private static String get(HttpClient client, EmbeddedServer server, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + path)).GET().build(),
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
