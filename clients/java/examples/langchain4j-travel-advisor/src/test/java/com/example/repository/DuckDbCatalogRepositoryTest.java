package com.example.repository;

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.model.vector.FloatVector;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuckDbCatalogRepositoryTest {
    @Test
    void updatesCatalogEmbeddingsThroughGeneratedRepositories() throws Exception {
        String url = "jdbc:duckdb:memory:catalog_update_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection keepAlive = DriverManager.getConnection(url, "", "")) {
            Flyway.configure().dataSource(url, "", "").locations("classpath:db/duckdb").load().migrate();
            try (ApplicationContext context = RepositoryTestContext.start(url)) {
                DestinationRepository destinations = context.getBean(DestinationRepository.class);
                HotelRepository hotels = context.getBean(HotelRepository.class);
                ActivityRepository activities = context.getBean(ActivityRepository.class);
                assertEquals(7, destinations.findByDescriptionEmbeddingIsNull().size());
                assertEquals(12, hotels.findByDescriptionEmbeddingIsNull().size());
                assertEquals(9, activities.findByDescriptionEmbeddingIsNull().size());

                FloatVector vector = new FloatVector(vector(1, 0));
                destinations.updateDescriptionEmbedding(1L, vector);
                hotels.updateDescriptionEmbedding(1L, vector);
                activities.updateDescriptionEmbedding(1L, vector);
                assertEquals(6, destinations.findByDescriptionEmbeddingIsNull().size());
                assertEquals(11, hotels.findByDescriptionEmbeddingIsNull().size());
                assertEquals(8, activities.findByDescriptionEmbeddingIsNull().size());
            }
        }
    }

    @Test
    void readsCatalogAndRanksEachUnfilteredSearch() throws Exception {
        String url = "jdbc:duckdb:memory:catalog_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection keepAlive = DriverManager.getConnection(url, "", "")) {
            Flyway.configure().dataSource(url, "", "").locations("classpath:db/duckdb").load().migrate();
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(url, "", ""));
            float[] query = vector(1, 0);
            for (String table : new String[]{"destinations", "hotels", "activities"}) {
                try (PreparedStatement statement = keepAlive.prepareStatement(
                        "UPDATE " + table + " SET description_embedding = ? WHERE id = ?")) {
                    for (int id = 1; id <= (table.equals("hotels") ? 12 : table.equals("activities") ? 9 : 7); id++) {
                        DuckDbVectorJdbc.bind(statement, 1, vector(id == 1 ? 1 : 0, id == 1 ? 0 : 1));
                        statement.setLong(2, id);
                        statement.executeUpdate();
                    }
                }
            }
            DuckDbExtensionManager extensions = new DuckDbExtensionManager(dataSource);
            extensions.prepare();
            extensions.validateEmbeddings();
            extensions.createSearchIndexes();
            try (ApplicationContext context = RepositoryTestContext.start(url)) {
                DestinationRepository destinations = context.getBean(DestinationRepository.class);
                HotelRepository hotels = context.getBean(HotelRepository.class);
                ActivityRepository activities = context.getBean(ActivityRepository.class);

                assertEquals("Zurich", destinations.findByNameEqualsIgnoreCase("zUrIcH").orElseThrow().name());
                assertEquals(8.5417, destinations.findById(7L).orElseThrow().location().x(), 0.00001);
                assertEquals(47.3769, destinations.findById(7L).orElseThrow().location().y(), 0.00001);
                assertEquals(7, destinations.findAll().size());
                assertTrue(destinations.findById(999L).isEmpty());
                assertEquals("Matterhorn View Hotel", hotels.findById(1L).orElseThrow().name());
                assertEquals(1L, hotels.findById(1L).orElseThrow().destinationId());
                assertEquals(280.0, hotels.findById(1L).orElseThrow().pricePerNight(), 0.01);
                assertEquals(7.7486, hotels.findById(1L).orElseThrow().location().x(), 0.00001);
                assertEquals("Gornergrat Railway Excursion", activities.findById(1L).orElseThrow().name());
                assertEquals("All Year", activities.findById(1L).orElseThrow().season());
                assertEquals(45.9839, activities.findById(1L).orElseThrow().location().y(), 0.00001);
                assertEquals(5, destinations.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 2.0).size());
                assertEquals(1L, destinations.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 2.0).getFirst().id());
                assertEquals(5, hotels.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 2.0).size());
                assertEquals(1L, hotels.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 2.0).getFirst().id());
                assertEquals(5, activities.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 2.0).size());
                assertEquals(1L, activities.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 2.0).getFirst().id());
                assertEquals(1, destinations.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 0.1).size());
                assertEquals(1, hotels.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 0.1).size());
                assertEquals(1, activities.findTop5ByDescriptionEmbeddingNear(new FloatVector(query), 0.1).size());
                for (String table : new String[]{"destinations", "hotels", "activities"}) {
                    try (PreparedStatement statement = keepAlive.prepareStatement(
                            "EXPLAIN SELECT * FROM " + table + " ORDER BY "
                                + "array_cosine_distance(description_embedding, CAST(? AS FLOAT[1536])) LIMIT 5")) {
                        statement.setString(1, DuckDbVectors.literal(query));
                        try (ResultSet rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            assertTrue(rows.getString(2).contains("HNSW_INDEX_SCAN"), rows.getString(2));
                        }
                    }
                }
            }
        }
    }

    private static float[] vector(float first, float second) {
        float[] result = new float[1536];
        result[0] = first;
        result[1] = second;
        return result;
    }
}
