package com.example.repository;

import jakarta.inject.Singleton;
import jakarta.annotation.PreDestroy;
import com.zaxxer.hikari.HikariDataSource;
import org.duckdb.DuckDBDriver;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Singleton
public class DuckDbExtensionManager {
    private final DataSource dataSource;
    private boolean prepared;
    private String databaseUrl;

    public DuckDbExtensionManager(DataSource dataSource) {
        this.dataSource = JdbcDataSources.raw(dataSource);
    }

    public synchronized void prepare() {
        if (prepared) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            databaseUrl = connection.getMetaData().getURL();
            statement.execute("INSTALL spatial");
            statement.execute("LOAD spatial");
            statement.execute("INSTALL vss");
            statement.execute("LOAD vss");
            prepared = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to install or load DuckDB spatial and vss extensions", e);
        }
    }

    @PreDestroy
    public synchronized void close() throws SQLException {
        // Release only after all pooled handles close; dependency destruction runs us first.
        dataSource.unwrap(HikariDataSource.class).close();
        if (databaseUrl != null) {
            DuckDBDriver.releaseDB(databaseUrl);
        }
    }

    public void validateEmbeddings() {
        validateDestinationLinks("hotels");
        validateDestinationLinks("activities");
        validateTable("destinations", 7);
        validateTable("hotels", 12);
        validateTable("activities", 9);
    }

    private void validateDestinationLinks(String table) {
        String sql = "SELECT COUNT(*) FROM " + table + " item LEFT JOIN destinations destination "
                + "ON item.destination_id = destination.id WHERE destination.id IS NULL";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            int orphanCount = rows.getInt(1);
            if (orphanCount != 0) {
                throw new IllegalStateException("Orphaned " + table + " destination links: " + orphanCount);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to validate " + table + " destination links", e);
        }
    }

    private void validateTable(String table, int expected) {
        String sql = "SELECT COUNT(*), COUNT(description_embedding), "
                + "COUNT(*) FILTER (WHERE array_length(description_embedding) = 1536) FROM " + table;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            if (rows.getInt(1) != expected || rows.getInt(2) != expected || rows.getInt(3) != expected) {
                throw new IllegalStateException("Incomplete " + table + " embeddings: expected " + expected
                        + " rows with 1536 dimensions, found " + rows.getInt(1) + " rows and "
                        + rows.getInt(3) + " valid embeddings");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to validate " + table + " embeddings", e);
        }
    }

    public void createSearchIndexes() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_destinations_description_embedding "
                    + "ON destinations USING HNSW (description_embedding) WITH (metric = 'cosine')");
            statement.execute("CREATE INDEX idx_hotels_description_embedding "
                    + "ON hotels USING HNSW (description_embedding) WITH (metric = 'cosine')");
            statement.execute("CREATE INDEX idx_activities_description_embedding "
                    + "ON activities USING HNSW (description_embedding) WITH (metric = 'cosine')");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create DuckDB cosine HNSW indexes", e);
        }
    }
}
