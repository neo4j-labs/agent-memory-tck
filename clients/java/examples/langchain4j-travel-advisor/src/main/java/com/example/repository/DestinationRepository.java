package com.example.repository;

import com.example.entity.DestinationEntity;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.geo.Point;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.model.vector.Vector;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.ANSI)
public interface DestinationRepository extends GenericRepository<DestinationEntity, Long> {
    double MAX_COSINE_DISTANCE = 2.0;
    String COLUMNS = "id, name, region, description, longitude, latitude";

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM destinations WHERE id = :id")
    Optional<DestinationEntity> selectById(Long id);

    default Optional<DestinationEntity> findById(Long id) {
        return selectById(id);
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM destinations ORDER BY id")
    List<DestinationEntity> selectAll();

    default List<DestinationEntity> findAll() {
        return selectAll();
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM destinations WHERE lower(name) = lower(:name)")
    Optional<DestinationEntity> selectByNameIgnoreCase(String name);

    default Optional<DestinationEntity> findByNameEqualsIgnoreCase(String name) {
        return selectByNameIgnoreCase(name);
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM destinations "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<DestinationEntity> searchIndexed(String embedding);

    @Connectable(readOnly = false)
    @Query("WITH ranked AS MATERIALIZED (SELECT " + COLUMNS
        + ", array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) AS distance "
        + "FROM destinations) SELECT " + COLUMNS + " FROM ranked WHERE distance <= :maxDistance "
        + "ORDER BY distance LIMIT 5")
    List<DestinationEntity> searchBounded(String embedding, Double maxDistance);

    default List<DestinationEntity> findTop5ByDescriptionEmbeddingNear(Vector embedding, Double maxDistance) {
        String value = DuckDbVectors.literal(embedding);
        List<DestinationEntity> rows = maxDistance == null || maxDistance >= MAX_COSINE_DISTANCE
            ? searchIndexed(value) : searchBounded(value, maxDistance);
        return rows;
    }

    default List<DestinationEntity> findTop5ByLocationNear(Point point, double distance) {
        throw new UnsupportedOperationException("Spatial destination search is not migrated yet");
    }

    @Connectable(readOnly = false)
    @Query("SELECT " + COLUMNS + " FROM destinations WHERE description_embedding IS NULL")
    List<DestinationEntity> selectWithoutEmbedding();

    default List<DestinationEntity> findByDescriptionEmbeddingIsNull() {
        return selectWithoutEmbedding();
    }

    @Query("UPDATE destinations SET description_embedding = CAST(:embedding AS FLOAT[1536]) WHERE id = :id")
    long updateEmbedding(Long id, String embedding);

    default void updateDescriptionEmbedding(Long id, Vector embedding) {
        updateEmbedding(id, DuckDbVectors.literal(embedding));
    }
}
