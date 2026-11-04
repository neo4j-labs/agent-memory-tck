package com.example.repository;

import com.example.entity.DestinationEntity;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.ANSI)
public interface EmbeddingBackfillRepository extends GenericRepository<DestinationEntity, Long> {
    @Connectable(readOnly = false)
    @Query("SELECT id, name, region, description FROM destinations WHERE description_embedding IS NULL")
    List<DestinationEmbeddingSeed> findDestinationsWithoutEmbedding();

    @Connectable(readOnly = false)
    @Query("""
        SELECT h.id, h.name, h.description, d.name AS destination_name
        FROM hotels h
        JOIN destinations d ON h.destination_id = d.id
        WHERE h.description_embedding IS NULL
        """)
    List<HotelEmbeddingSeed> findHotelsWithoutEmbedding();

    @Connectable(readOnly = false)
    @Query("""
        SELECT a.id, a.name, a.season, a.description, d.name AS destination_name
        FROM activities a
        JOIN destinations d ON a.destination_id = d.id
        WHERE a.description_embedding IS NULL
        """)
    List<ActivityEmbeddingSeed> findActivitiesWithoutEmbedding();

    @Query("UPDATE destinations SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    long writeDestinationEmbedding(Long id, String embedding);

    default void updateDestinationEmbedding(Long id, float[] embedding) {
        requireUpdated("destinations", id, writeDestinationEmbedding(id, DuckDbVectors.literal(embedding)));
    }

    @Query("UPDATE hotels SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    long writeHotelEmbedding(Long id, String embedding);

    default void updateHotelEmbedding(Long id, float[] embedding) {
        requireUpdated("hotels", id, writeHotelEmbedding(id, DuckDbVectors.literal(embedding)));
    }

    @Query("UPDATE activities SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    long writeActivityEmbedding(Long id, String embedding);

    default void updateActivityEmbedding(Long id, float[] embedding) {
        requireUpdated("activities", id, writeActivityEmbedding(id, DuckDbVectors.literal(embedding)));
    }

    private static void requireUpdated(String table, Long id, long count) {
        if (count != 1) {
            throw new IllegalStateException("No " + table + " row found for id=" + id);
        }
    }

    @Introspected
    record DestinationEmbeddingSeed(Long id, String name, String region, String description) {
    }

    @Introspected
    record HotelEmbeddingSeed(Long id, String name, String destinationName, String description) {
    }

    @Introspected
    record ActivityEmbeddingSeed(Long id, String name, String destinationName, String season, String description) {
    }
}
