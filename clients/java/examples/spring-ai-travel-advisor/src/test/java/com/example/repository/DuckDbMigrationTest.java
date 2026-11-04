package com.example.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbMigrationTest {

    @Test
    void removesOnlyChatMemorySchema() throws SQLException {
        String url = "jdbc:duckdb:memory:migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection keepAlive = DriverManager.getConnection(url, "", "")) {
            Flyway.configure().dataSource(url, "", "")
                    .locations("classpath:db/duckdb").load().migrate();

            assertEquals(7, count(keepAlive, "destinations"));
            assertEquals(12, count(keepAlive, "hotels"));
            assertEquals(9, count(keepAlive, "activities"));
            assertEquals(0, scalar(keepAlive, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'chat_memory'"));
            assertEquals(0, count(keepAlive, "wishlist_items"));

            assertEquals(7, scalar(keepAlive, "SELECT COUNT(*) FROM destinations WHERE id BETWEEN 1 AND 7"));
            assertEquals(12, scalar(keepAlive, "SELECT COUNT(*) FROM hotels WHERE id BETWEEN 1 AND 12"));
            assertEquals(9, scalar(keepAlive, "SELECT COUNT(*) FROM activities WHERE id BETWEEN 1 AND 9"));
            assertEquals(0, scalar(keepAlive, "SELECT COUNT(*) FROM hotels h LEFT JOIN destinations d ON d.id = h.destination_id WHERE d.id IS NULL"));
            assertEquals(0, scalar(keepAlive, "SELECT COUNT(*) FROM activities a LEFT JOIN destinations d ON d.id = a.destination_id WHERE d.id IS NULL"));
            assertEquals("1,1,2,2,3,3,4,5,5,6,7,7", scalarString(keepAlive,
                    "SELECT string_agg(destination_id::VARCHAR, ',' ORDER BY id) FROM hotels"));
            assertEquals("1,2,2,3,4,5,7,7,7", scalarString(keepAlive,
                    "SELECT string_agg(destination_id::VARCHAR, ',' ORDER BY id) FROM activities"));
            assertEquals("Zermatt|Interlaken|Lucerne|Lausanne|St. Moritz|Lugano|Zurich", scalarString(keepAlive,
                    "SELECT string_agg(name, '|' ORDER BY id) FROM destinations"));
            assertEquals("Matterhorn View Hotel|Alpine Lodge Zermatt|Interlaken Grand Resort|Adventure Base Hostel|"
                    + "Lucerne Palace Hotel|Old Town Guesthouse|Lausanne Design Hotel|St. Moritz Palace|"
                    + "Mountain Comfort Inn|Lugano Lakeside Retreat|Zurich Old Town Boutique|Lake Zurich Guesthouse",
                    scalarString(keepAlive, "SELECT string_agg(name, '|' ORDER BY id) FROM hotels"));
            assertEquals("Gornergrat Railway Excursion|Jungfraujoch - Top of Europe|Paragliding Over Interlaken|"
                    + "Mount Pilatus Golden Round Trip|Lavaux Vineyard Terraces Tour|Bernina Express Scenic Train|"
                    + "Lake Zurich Promenade and Boat Cruise|Uetliberg Panorama Hike|Kunsthaus and Old Town Walk",
                    scalarString(keepAlive, "SELECT string_agg(name, '|' ORDER BY id) FROM activities"));
            assertEquals(2, scalar(keepAlive, "SELECT COUNT(*) FROM hotels WHERE destination_id = 7"));
            assertEquals(3, scalar(keepAlive, "SELECT COUNT(*) FROM activities WHERE destination_id = 7"));

            try (Statement statement = keepAlive.createStatement();
                 ResultSet result = statement.executeQuery("SELECT longitude, latitude FROM destinations WHERE id = 7 AND name = 'Zurich'")) {
                assertTrue(result.next());
                assertEquals(8.5417, result.getDouble(1), 0.000001);
                assertEquals(47.3769, result.getDouble(2), 0.000001);
            }
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        return scalar(connection, "SELECT COUNT(*) FROM " + table);
    }

    private static int scalar(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String scalarString(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            result.next();
            return result.getString(1);
        }
    }
}
