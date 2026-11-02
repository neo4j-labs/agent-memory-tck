package com.example.repository;

import com.example.entity.DestinationEntity;

import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Transactional
public interface DestinationRepository extends Repository<DestinationEntity, Long> {
    double MAX_COSINE_DISTANCE = 2.0;
    String COLUMNS = "id, name, region, description, longitude, latitude";

    @Query("SELECT " + COLUMNS + " FROM destinations WHERE id = :id")
    Optional<DestinationEntity> selectById(@Param("id") Long id);

    default Optional<DestinationEntity> findById(Long id) {
        return selectById(id);
    }

    @Query("SELECT " + COLUMNS + " FROM destinations ORDER BY id")
    List<DestinationEntity> selectAll();

    default List<DestinationEntity> findAll() {
        return selectAll();
    }

    @Query("SELECT " + COLUMNS + " FROM destinations WHERE lower(name) = lower(:name)")
    Optional<DestinationEntity> selectByNameIgnoreCase(@Param("name") String name);

    default Optional<DestinationEntity> findByNameEqualsIgnoreCase(String name) {
        return selectByNameIgnoreCase(name);
    }

    @Query("SELECT " + COLUMNS + " FROM destinations "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<DestinationEntity> searchIndexed(@Param("embedding") String embedding);

    @Query("WITH ranked AS MATERIALIZED (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM destinations) SELECT " + COLUMNS + " FROM ranked WHERE distance <= :maxDistance "
        + "ORDER BY distance LIMIT 5")
    List<DestinationEntity> searchBounded(@Param("embedding") String embedding, @Param("maxDistance") Double maxDistance);

    default List<DestinationEntity> findTop5ByDescriptionEmbeddingNear(float[] embedding, Double maxDistance) {
        String value = DuckDbVectors.literal(embedding);
        List<DestinationEntity> rows = maxDistance == null || maxDistance >= MAX_COSINE_DISTANCE
            ? searchIndexed(value) : searchBounded(value, maxDistance);
        return rows;
    }

    @Query("SELECT " + COLUMNS + " FROM destinations WHERE description_embedding IS NULL")
    List<DestinationEntity> selectWithoutEmbedding();

    default List<DestinationEntity> findByDescriptionEmbeddingIsNull() {
        return selectWithoutEmbedding();
    }

    @Modifying
    @Query("UPDATE destinations SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    default void updateDescriptionEmbedding(Long id, float[] embedding) {
        updateEmbedding(id, DuckDbVectors.literal(embedding));
    }
}
