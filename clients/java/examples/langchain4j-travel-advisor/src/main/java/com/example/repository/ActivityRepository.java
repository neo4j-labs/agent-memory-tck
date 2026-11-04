package com.example.repository;

import com.example.entity.ActivityEntity;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.model.vector.Vector;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.ANSI)
public interface ActivityRepository extends GenericRepository<ActivityEntity, Long> {
    String COLUMNS = "id, destination_id, name, season, description, longitude, latitude";

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM activities WHERE id = :id")
    Optional<ActivityEntity> selectById(Long id);

    default Optional<ActivityEntity> findById(Long id) {
        return selectById(id);
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM activities "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<ActivityEntity> searchIndexed(String embedding);

    @Connectable(readOnly = false)
    @Query("WITH ranked AS MATERIALIZED (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM activities) SELECT " + COLUMNS + " FROM ranked WHERE distance <= :maxDistance "
        + "ORDER BY distance LIMIT 5")
    List<ActivityEntity> searchBounded(String embedding, Double maxDistance);

    default List<ActivityEntity> findTop5ByDescriptionEmbeddingNear(Vector embedding, Double maxDistance) {
        String value = DuckDbVectors.literal(embedding);
        List<ActivityEntity> rows = maxDistance == null || maxDistance >= DestinationRepository.MAX_COSINE_DISTANCE
            ? searchIndexed(value) : searchBounded(value, maxDistance);
        return rows;
    }

    @Connectable(readOnly = false)
    @Query("WITH filtered AS MATERIALIZED (SELECT " + COLUMNS + ", description_embedding FROM activities "
        + "WHERE destination_id = :destinationId AND description_embedding IS NOT NULL), "
        + "ranked AS (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM filtered) SELECT " + COLUMNS + " FROM ranked "
        + "WHERE distance <= COALESCE(:maxDistance, 2.0) ORDER BY distance LIMIT 5")
    List<ActivityEntity> searchWithinDestination(Long destinationId, String embedding, @Nullable Double maxDistance);

    default List<ActivityEntity> findTop5ByDestinationIdAndDescriptionEmbeddingNear(
            Long destinationId, Vector embedding, Double maxDistance) {
        Double bound = maxDistance != null && maxDistance < DestinationRepository.MAX_COSINE_DISTANCE
            ? maxDistance : null;
        return searchWithinDestination(destinationId, DuckDbVectors.literal(embedding), bound);
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM activities WHERE description_embedding IS NULL")
    List<ActivityEntity> selectWithoutEmbedding();

    default List<ActivityEntity> findByDescriptionEmbeddingIsNull() {
        return selectWithoutEmbedding();
    }

    @Query("UPDATE activities SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    long updateEmbedding(Long id, String embedding);

    default void updateDescriptionEmbedding(Long id, Vector embedding) {
        updateEmbedding(id, DuckDbVectors.literal(embedding));
    }
}
