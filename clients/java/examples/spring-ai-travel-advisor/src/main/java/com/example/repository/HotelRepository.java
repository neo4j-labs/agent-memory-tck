package com.example.repository;

import com.example.entity.HotelEntity;

import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Transactional
public interface HotelRepository extends Repository<HotelEntity, Long> {
    String COLUMNS = "id, destination_id, name, price_per_night, description, longitude, latitude";

    @Query("SELECT " + COLUMNS + " FROM hotels WHERE id = :id")
    Optional<HotelEntity> selectById(@Param("id") Long id);

    default Optional<HotelEntity> findById(Long id) {
        return selectById(id);
    }

    @Query("SELECT " + COLUMNS + " FROM hotels "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<HotelEntity> searchIndexed(@Param("embedding") String embedding);

    @Query("WITH ranked AS MATERIALIZED (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM hotels) SELECT " + COLUMNS + " FROM ranked WHERE distance <= :maxDistance "
        + "ORDER BY distance LIMIT 5")
    List<HotelEntity> searchBounded(@Param("embedding") String embedding, @Param("maxDistance") Double maxDistance);

    default List<HotelEntity> findTop5ByDescriptionEmbeddingNear(float[] embedding, Double maxDistance) {
        String value = DuckDbVectors.literal(embedding);
        List<HotelEntity> rows = maxDistance == null || maxDistance >= DestinationRepository.MAX_COSINE_DISTANCE
            ? searchIndexed(value) : searchBounded(value, maxDistance);
        return rows;
    }

    default List<HotelEntity> findTop5ByDestinationIdAndDescriptionEmbeddingNear(
            Long destinationId, float[] embedding, Double maxDistance) {
        return searchFiltered(destinationId, null, embedding, maxDistance);
    }

    default List<HotelEntity> findTop5ByPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(
            Double maxPrice, float[] embedding, Double maxDistance) {
        return searchFiltered(null, maxPrice, embedding, maxDistance);
    }

    default List<HotelEntity> findTop5ByDestinationIdAndPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(
            Long destinationId, Double maxPrice, float[] embedding, Double maxDistance) {
        return searchFiltered(destinationId, maxPrice, embedding, maxDistance);
    }

    @Query("WITH filtered AS MATERIALIZED (SELECT " + COLUMNS + ", description_embedding FROM hotels "
        + "WHERE description_embedding IS NOT NULL "
        + "AND destination_id = COALESCE(:destinationId, destination_id) "
        + "AND price_per_night <= COALESCE(:maxPrice, price_per_night)), "
        + "ranked AS (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM filtered) SELECT " + COLUMNS + " FROM ranked "
        + "WHERE distance <= COALESCE(:maxDistance, 2.0) ORDER BY distance LIMIT 5")
    List<HotelEntity> queryFiltered(@Param("destinationId") Long destinationId, @Param("maxPrice") Double maxPrice,
                                      @Param("embedding") String embedding, @Param("maxDistance") Double maxDistance);

    default List<HotelEntity> searchFiltered(Long destinationId, Double maxPrice,
                                             float[] embedding, Double maxDistance) {
        Double bound = maxDistance != null && maxDistance < DestinationRepository.MAX_COSINE_DISTANCE
            ? maxDistance : null;
        return queryFiltered(destinationId, maxPrice, DuckDbVectors.literal(embedding), bound);
    }

    @Query("SELECT " + COLUMNS + " FROM hotels WHERE description_embedding IS NULL")
    List<HotelEntity> selectWithoutEmbedding();

    default List<HotelEntity> findByDescriptionEmbeddingIsNull() {
        return selectWithoutEmbedding();
    }

    @Modifying
    @Query("UPDATE hotels SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    default void updateDescriptionEmbedding(Long id, float[] embedding) {
        updateEmbedding(id, DuckDbVectors.literal(embedding));
    }
}
