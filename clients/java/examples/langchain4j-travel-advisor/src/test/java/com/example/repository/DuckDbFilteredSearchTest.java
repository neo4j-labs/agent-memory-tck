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
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuckDbFilteredSearchTest {
    @Test
    void filtersBeforeRankingAndKeepsFiveEligibleMatches() throws Exception {
        String url = "jdbc:duckdb:memory:filtered_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection keepAlive = DriverManager.getConnection(url, "", "")) {
            Flyway.configure().dataSource(url, "", "").locations("classpath:db/duckdb").load().migrate();
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(url, "", ""));
            new DuckDbExtensionManager(dataSource).prepare();
            try (Statement statement = keepAlive.createStatement()) {
                statement.execute("SET geometry_always_xy=true");
            }
            when(dataSource.getConnection()).thenAnswer(invocation -> configuredConnection(url));
            try (Statement statement = keepAlive.createStatement()) {
                statement.execute("DELETE FROM activities");
                statement.execute("DELETE FROM hotels");
                statement.execute("DELETE FROM destinations");
            }
            for (long id = 1; id <= 110; id++) {
                boolean eligible = id > 100;
                float[] embedding = vector(1, eligible ? (id - 100) * 0.1f : 0);
                double longitude = eligible ? 8.54 : 7.0;
                double latitude = eligible ? 47.37 : 46.0;
                insertDestination(keepAlive, id, longitude, latitude, embedding);
                if (id <= 10 || eligible) {
                    insertHotel(keepAlive, id, eligible ? 7 : 1, eligible ? 100 : 500,
                            longitude, latitude, embedding);
                    insertActivity(keepAlive, id, eligible ? 7 : 1, longitude, latitude, embedding);
                }
            }
            insertDestination(keepAlive, 200, 7.86, 46.68, vector(1, 2));
            insertHotel(keepAlive, 200, 2, 120, 7.86, 46.68, vector(1, 2));
            insertActivity(keepAlive, 200, 2, 7.86, 46.68, vector(1, 2));
            insertDestination(keepAlive, 201, 7.86, 46.77, vector(1, 2.1f));
            insertHotel(keepAlive, 201, 2, 120, 7.86, 46.77, vector(1, 2.1f));
            insertActivity(keepAlive, 201, 2, 7.86, 46.77, vector(1, 2.1f));
            new DuckDbExtensionManager(dataSource).createSearchIndexes();
            FloatVector query = new FloatVector(vector(1, 0));
            try (ApplicationContext context = RepositoryTestContext.start(url)) {
                SpatialSearchRepository spatial = context.getBean(SpatialSearchRepository.class);
                HotelRepository hotels = context.getBean(HotelRepository.class);
                ActivityRepository activities = context.getBean(ActivityRepository.class);
                List<Long> expected = List.of(101L, 102L, 103L, 104L, 105L);

                assertEquals(expected, spatial.searchDestinationsByVectorNear(query, 8.54, 47.37, 50.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(expected, spatial.searchHotelsByVectorNear(query, 8.54, 47.37, 50.0, 150.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(expected, spatial.searchActivitiesByVectorNear(query, 8.54, 47.37, 50.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(List.of(200L, 201L), spatial.searchDestinationsByVectorNear(query, 7.86, 46.68, 15.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(List.of(200L, 201L), spatial.searchHotelsByVectorNear(query, 7.86, 46.68, 15.0, null)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(List.of(200L, 201L), spatial.searchActivitiesByVectorNear(query, 7.86, 46.68, 15.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(List.of(200L), spatial.searchDestinationsByVectorNear(query, 7.86, 46.68, 5.0)
                        .stream().map(entity -> entity.id()).toList());
                // A 0.10-degree east/west separation here is about 7.6 km, but about 11.1 km if axes are swapped.
                assertEquals(List.of(200L), spatial.searchDestinationsByVectorNear(query, 7.96, 46.68, 9.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(List.of(200L), spatial.searchHotelsByVectorNear(query, 7.96, 46.68, 9.0, null)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(List.of(200L), spatial.searchActivitiesByVectorNear(query, 7.96, 46.68, 9.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(expected, hotels.findTop5ByDestinationIdAndDescriptionEmbeddingNear(7L, query, 2.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(expected, hotels.findTop5ByPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(150.0, query, 2.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(expected, hotels.findTop5ByDestinationIdAndPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(7L, 150.0, query, 2.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(expected, activities.findTop5ByDestinationIdAndDescriptionEmbeddingNear(7L, query, 2.0)
                        .stream().map(entity -> entity.id()).toList());
                assertEquals(1, hotels.findTop5ByPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(150.0, query, 0.006)
                        .size());
                assertNoHnswInsideFilteredPlan(keepAlive, query);
            }
        }
    }

    private static Connection configuredConnection(String url) throws Exception {
        Connection connection = DriverManager.getConnection(url, "", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET geometry_always_xy=true");
        }
        return connection;
    }

    private static void insertDestination(Connection connection, long id, double lon, double lat, float[] vector) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO destinations VALUES (?, 'Destination', 'Region', 'Description', ?, ?, ?)")) {
            statement.setLong(1, id);
            DuckDbVectorJdbc.bind(statement, 2, vector);
            statement.setDouble(3, lon);
            statement.setDouble(4, lat);
            statement.executeUpdate();
        }
    }

    private static void insertHotel(Connection connection, long id, long destinationId, double price,
                                    double lon, double lat, float[] vector) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO hotels VALUES (?, ?, 'Hotel', ?, 'Description', ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, destinationId);
            statement.setDouble(3, price);
            DuckDbVectorJdbc.bind(statement, 4, vector);
            statement.setDouble(5, lon);
            statement.setDouble(6, lat);
            statement.executeUpdate();
        }
    }

    private static void insertActivity(Connection connection, long id, long destinationId,
                                       double lon, double lat, float[] vector) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO activities VALUES (?, ?, 'Activity', 'All Year', 'Description', ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, destinationId);
            DuckDbVectorJdbc.bind(statement, 3, vector);
            statement.setDouble(4, lon);
            statement.setDouble(5, lat);
            statement.executeUpdate();
        }
    }

    private static void assertNoHnswInsideFilteredPlan(Connection connection, FloatVector query) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                EXPLAIN WITH filtered AS MATERIALIZED (
                    SELECT id, description_embedding FROM destinations
                    WHERE ST_DWithin_Spheroid(ST_Point2D(longitude, latitude), ST_Point2D(?, ?), ?)
                ) SELECT id FROM filtered ORDER BY array_cosine_distance(description_embedding, CAST(? AS FLOAT[1536])) LIMIT 5
                """)) {
            statement.setDouble(1, 8.54);
            statement.setDouble(2, 47.37);
            statement.setDouble(3, 50000);
            statement.setString(4, DuckDbVectors.literal(query));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertFalse(rows.getString(2).contains("HNSW_INDEX_SCAN"), rows.getString(2));
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
