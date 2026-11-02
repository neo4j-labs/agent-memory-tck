package com.example.repository;

import com.example.entity.HotelEntity;
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
public interface HotelRepository extends GenericRepository<HotelEntity, Long> {
    String COLUMNS = "id, destination_id, name, price_per_night, description, longitude, latitude";

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM hotels WHERE id = :id")
    Optional<HotelEntity> selectById(Long id);

    default Optional<HotelEntity> findById(Long id) {
        return selectById(id);
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM hotels "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<HotelEntity> searchIndexed(String embedding);

    @Connectable(readOnly = false)
    @Query("WITH ranked AS MATERIALIZED (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM hotels) SELECT " + COLUMNS + " FROM ranked WHERE distance <= :maxDistance "
        + "ORDER BY distance LIMIT 5")
    List<HotelEntity> searchBounded(String embedding, Double maxDistance);

    default List<HotelEntity> findTop5ByDescriptionEmbeddingNear(Vector embedding, Double maxDistance) {
        String value = DuckDbVectors.literal(embedding);
        List<HotelEntity> rows = maxDistance == null || maxDistance >= DestinationRepository.MAX_COSINE_DISTANCE
            ? searchIndexed(value) : searchBounded(value, maxDistance);
        return rows;
    }

    default List<HotelEntity> findTop5ByDestinationIdAndDescriptionEmbeddingNear(
            Long destinationId, Vector embedding, Double maxDistance) {
        return searchFiltered(destinationId, null, embedding, maxDistance);
    }

    default List<HotelEntity> findTop5ByPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(
            Double maxPrice, Vector embedding, Double maxDistance) {
        return searchFiltered(null, maxPrice, embedding, maxDistance);
    }

    default List<HotelEntity> findTop5ByDestinationIdAndPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(
            Long destinationId, Double maxPrice, Vector embedding, Double maxDistance) {
        return searchFiltered(destinationId, maxPrice, embedding, maxDistance);
    }

    @Connectable(readOnly = false)
    @Query("WITH filtered AS MATERIALIZED (SELECT " + COLUMNS + ", description_embedding FROM hotels "
        + "WHERE description_embedding IS NOT NULL "
        + "AND destination_id = COALESCE(:destinationId, destination_id) "
        + "AND price_per_night <= COALESCE(:maxPrice, price_per_night)), "
        + "ranked AS (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM filtered) SELECT " + COLUMNS + " FROM ranked "
        + "WHERE distance <= COALESCE(:maxDistance, 2.0) ORDER BY distance LIMIT 5")
    List<HotelEntity> queryFiltered(@Nullable Long destinationId, @Nullable Double maxPrice,
                                      String embedding, @Nullable Double maxDistance);

    default List<HotelEntity> searchFiltered(Long destinationId, Double maxPrice,
                                             Vector embedding, Double maxDistance) {
        Double bound = maxDistance != null && maxDistance < DestinationRepository.MAX_COSINE_DISTANCE
            ? maxDistance : null;
        return queryFiltered(destinationId, maxPrice, DuckDbVectors.literal(embedding), bound);
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM hotels WHERE description_embedding IS NULL")
    List<HotelEntity> selectWithoutEmbedding();

    default List<HotelEntity> findByDescriptionEmbeddingIsNull() {
        return selectWithoutEmbedding();
    }

    @Query("UPDATE hotels SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    long updateEmbedding(Long id, String embedding);

    default void updateDescriptionEmbedding(Long id, Vector embedding) {
        updateEmbedding(id, DuckDbVectors.literal(embedding));
    }
}
