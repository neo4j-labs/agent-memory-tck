package com.example.repository;

import com.example.service.DataInitializer;
import com.example.service.EmbeddingService;
import io.micronaut.context.ApplicationContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DuckDbInitializationTest {

    @Test
    void initializesEmbeddingsAndCosineIndexes() throws Exception {
        String url = "jdbc:duckdb:memory:initialization_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection keepAlive = DriverManager.getConnection(url, "", "")) {
            Flyway.configure().dataSource(url, "", "")
                    .locations("classpath:db/duckdb").load().migrate();
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(url, "", ""));
            EmbeddingService embeddingService = mock(EmbeddingService.class);
            float[] embedding = new float[1536];
            embedding[0] = 1.0f;
            when(embeddingService.generateEmbedding(anyString())).thenReturn(embedding);

            try (ApplicationContext context = RepositoryTestContext.start(url)) {
                EmbeddingBackfillRepository repository = context.getBean(EmbeddingBackfillRepository.class);
                assertEquals("Zermatt", repository.findHotelsWithoutEmbedding().getFirst().destinationName());
                assertEquals("Zermatt", repository.findActivitiesWithoutEmbedding().getFirst().destinationName());
                DataInitializer initializer = new DataInitializer(embeddingService,
                    repository, new DuckDbExtensionManager(dataSource));
                initializer.initialize();
                IllegalStateException missing = assertThrows(IllegalStateException.class,
                    () -> repository.updateDestinationEmbedding(999L, embedding));
                assertTrue(missing.getMessage().contains("No destinations row found for id=999"));
            }

            verify(embeddingService, times(28)).generateEmbedding(anyString());
            assertComplete(keepAlive, "destinations", 7);
            assertComplete(keepAlive, "hotels", 12);
            assertComplete(keepAlive, "activities", 9);
            try (Statement statement = keepAlive.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT ST_AsText(ST_Point(8.5, 47.3))")) {
                assertTrue(rows.next());
                assertEquals("POINT (8.5 47.3)", rows.getString(1));
            }
            try (Statement statement = keepAlive.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT string_agg(index_name, ',' ORDER BY index_name) "
                         + "FROM duckdb_indexes() WHERE index_name LIKE 'idx_%_description_embedding'")) {
                assertTrue(rows.next());
                assertEquals("idx_activities_description_embedding,idx_destinations_description_embedding,"
                        + "idx_hotels_description_embedding", rows.getString(1));
            }
            for (String table : new String[]{"destinations", "hotels", "activities"}) {
                try (PreparedStatement statement = keepAlive.prepareStatement(
                        "EXPLAIN SELECT id FROM " + table + " ORDER BY "
                                + "array_cosine_distance(description_embedding, ?::FLOAT[1536]) LIMIT 5")) {
                    DuckDbVectorJdbc.bind(statement, 1, embedding);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(rows.getString(2).contains("HNSW_INDEX_SCAN"), rows.getString(2));
                    }
                }
            }
        }
    }

    @Test
    void rejectsWrongSizeBeforeJDBCExecution() {
        PreparedStatement statement = mock(PreparedStatement.class);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DuckDbVectorJdbc.bind(statement, 1, new float[1535]));
        assertEquals("embedding must contain 1536 floats; received 1535", failure.getMessage());
        IllegalArgumentException repositoryFailure = assertThrows(IllegalArgumentException.class,
                () -> DuckDbVectors.literal(new float[1535]));
        assertEquals(failure.getMessage(), repositoryFailure.getMessage());
        verifyNoInteractions(statement);
    }

    @Test
    void propagatesTheFailingStartupStage() {
        EmbeddingBackfillRepository repository = mock(EmbeddingBackfillRepository.class);
        DuckDbExtensionManager extensions = mock(DuckDbExtensionManager.class);
        when(repository.findDestinationsWithoutEmbedding()).thenThrow(new IllegalStateException("database unavailable"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DataInitializer(mock(EmbeddingService.class), repository, extensions).initialize());
        assertTrue(failure.getMessage().contains("destination embedding backfill"));
        assertEquals("database unavailable", failure.getCause().getMessage());
    }

    @Test
    void rejectsOrphanedCatalogLinksBeforeBuildingIndexes() throws Exception {
        for (String table : new String[]{"hotels", "activities"}) {
            String url = "jdbc:duckdb:memory:orphan_test_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection keepAlive = DriverManager.getConnection(url, "", "")) {
                Flyway.configure().dataSource(url, "", "")
                        .locations("classpath:db/duckdb").load().migrate();
                try (Statement statement = keepAlive.createStatement()) {
                    statement.executeUpdate("UPDATE " + table + " SET destination_id = 999 WHERE id = 1");
                }
                DataSource dataSource = mock(DataSource.class);
                when(dataSource.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(url, "", ""));
                EmbeddingService embeddingService = mock(EmbeddingService.class);
                float[] embedding = new float[1536];
                embedding[0] = 1.0f;
                when(embeddingService.generateEmbedding(anyString())).thenReturn(embedding);
                try (ApplicationContext context = RepositoryTestContext.start(url)) {
                    DataInitializer initializer = new DataInitializer(embeddingService,
                        context.getBean(EmbeddingBackfillRepository.class), new DuckDbExtensionManager(dataSource));

                    IllegalStateException failure = assertThrows(IllegalStateException.class, initializer::initialize);
                    assertTrue(failure.getMessage().contains("embedding validation"));
                    assertTrue(failure.getCause().getMessage().contains("Orphaned " + table + " destination links"),
                        failure.getCause().getMessage());
                }
            }
        }
    }

    private static void assertComplete(Connection connection, String table, int expected) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*), COUNT(description_embedding), "
                     + "COUNT(*) FILTER (WHERE array_length(description_embedding) = 1536) FROM " + table)) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1));
            assertEquals(expected, rows.getInt(2));
            assertEquals(expected, rows.getInt(3));
        }
    }
}
