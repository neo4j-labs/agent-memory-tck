package com.example.repository;

import com.example.entity.DestinationEntity;

import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface EmbeddingBackfillRepository extends Repository<DestinationEntity, Long> {
    @Query("SELECT id, name, region, description FROM destinations WHERE description_embedding IS NULL")
    List<DestinationEmbeddingSeed> findDestinationsWithoutEmbedding();

    @Query("""
        SELECT h.id, h.name, h.description, d.name AS destination_name
        FROM hotels h
        JOIN destinations d ON h.destination_id = d.id
        WHERE h.description_embedding IS NULL
        """)
    List<HotelEmbeddingSeed> findHotelsWithoutEmbedding();

    @Query("""
        SELECT a.id, a.name, a.season, a.description, d.name AS destination_name
        FROM activities a
        JOIN destinations d ON a.destination_id = d.id
        WHERE a.description_embedding IS NULL
        """)
    List<ActivityEmbeddingSeed> findActivitiesWithoutEmbedding();

    @Modifying
    @Query("UPDATE destinations SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    int writeDestinationEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    default void updateDestinationEmbedding(Long id, float[] embedding) {
        requireUpdated("destinations", id, writeDestinationEmbedding(id, DuckDbVectors.literal(embedding)));
    }

    @Modifying
    @Query("UPDATE hotels SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    int writeHotelEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    default void updateHotelEmbedding(Long id, float[] embedding) {
        requireUpdated("hotels", id, writeHotelEmbedding(id, DuckDbVectors.literal(embedding)));
    }

    @Modifying
    @Query("UPDATE activities SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    int writeActivityEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    default void updateActivityEmbedding(Long id, float[] embedding) {
        requireUpdated("activities", id, writeActivityEmbedding(id, DuckDbVectors.literal(embedding)));
    }

    private static void requireUpdated(String table, Long id, long count) {
        if (count != 1) {
            throw new IllegalStateException("No " + table + " row found for id=" + id);
        }
    }

    record DestinationEmbeddingSeed(Long id, String name, String region, String description) {
    }

    record HotelEmbeddingSeed(Long id, String name, String destinationName, String description) {
    }

    record ActivityEmbeddingSeed(Long id, String name, String destinationName, String season, String description) {
    }
}
