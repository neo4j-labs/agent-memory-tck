package com.example.repository;

import com.example.entity.ActivityEntity;

import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Transactional
public interface ActivityRepository extends Repository<ActivityEntity, Long> {
    String COLUMNS = "id, destination_id, name, season, description, longitude, latitude";

    @Query("SELECT " + COLUMNS + " FROM activities WHERE id = :id")
    Optional<ActivityEntity> selectById(@Param("id") Long id);

    default Optional<ActivityEntity> findById(Long id) {
        return selectById(id);
    }

    @Query("SELECT " + COLUMNS + " FROM activities "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<ActivityEntity> searchIndexed(@Param("embedding") String embedding);

    @Query("WITH ranked AS MATERIALIZED (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM activities) SELECT " + COLUMNS + " FROM ranked WHERE distance <= :maxDistance "
        + "ORDER BY distance LIMIT 5")
    List<ActivityEntity> searchBounded(@Param("embedding") String embedding, @Param("maxDistance") Double maxDistance);

    default List<ActivityEntity> findTop5ByDescriptionEmbeddingNear(float[] embedding, Double maxDistance) {
        String value = DuckDbVectors.literal(embedding);
        List<ActivityEntity> rows = maxDistance == null || maxDistance >= DestinationRepository.MAX_COSINE_DISTANCE
            ? searchIndexed(value) : searchBounded(value, maxDistance);
        return rows;
    }

    @Query("WITH filtered AS MATERIALIZED (SELECT " + COLUMNS + ", description_embedding FROM activities "
        + "WHERE destination_id = :destinationId AND description_embedding IS NOT NULL), "
        + "ranked AS (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM filtered) SELECT " + COLUMNS + " FROM ranked "
        + "WHERE distance <= COALESCE(:maxDistance, 2.0) ORDER BY distance LIMIT 5")
    List<ActivityEntity> searchWithinDestination(@Param("destinationId") Long destinationId, @Param("embedding") String embedding, @Param("maxDistance") Double maxDistance);

    default List<ActivityEntity> findTop5ByDestinationIdAndDescriptionEmbeddingNear(
            Long destinationId, float[] embedding, Double maxDistance) {
        Double bound = maxDistance != null && maxDistance < DestinationRepository.MAX_COSINE_DISTANCE
            ? maxDistance : null;
        return searchWithinDestination(destinationId, DuckDbVectors.literal(embedding), bound);
    }

    @Query("SELECT " + COLUMNS + " FROM activities WHERE description_embedding IS NULL")
    List<ActivityEntity> selectWithoutEmbedding();

    default List<ActivityEntity> findByDescriptionEmbeddingIsNull() {
        return selectWithoutEmbedding();
    }

    @Modifying
    @Query("UPDATE activities SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    default void updateDescriptionEmbedding(Long id, float[] embedding) {
        updateEmbedding(id, DuckDbVectors.literal(embedding));
    }
}
