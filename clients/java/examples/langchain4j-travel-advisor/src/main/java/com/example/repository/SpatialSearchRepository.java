package com.example.repository;

import com.example.entity.ActivityEntity;
import com.example.entity.DestinationEntity;
import com.example.entity.HotelEntity;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.model.vector.Vector;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.ANSI)
public interface SpatialSearchRepository extends GenericRepository<DestinationEntity, Long> {
    String WITHIN_RADIUS = "ST_DWithin_Spheroid(ST_Point2D(longitude, latitude), "
        + "ST_Point2D(:longitude, :latitude), :radiusMeters)";

    @Connectable(readOnly = false)
    @Query("WITH filtered AS MATERIALIZED (SELECT " + DestinationRepository.COLUMNS
        + ", description_embedding FROM destinations WHERE description_embedding IS NOT NULL AND "
        + WITHIN_RADIUS + ") SELECT " + DestinationRepository.COLUMNS + " FROM filtered "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<DestinationEntity> queryDestinations(String embedding, double longitude, double latitude,
                                               double radiusMeters);

    default List<DestinationEntity> searchDestinationsByVectorNear(Vector embedding, double longitude,
                                                                    double latitude, double radiusKm) {
        return queryDestinations(DuckDbVectors.literal(embedding), longitude, latitude, radiusKm * 1000);
    }

    @Connectable(readOnly = false)
    @Query("WITH filtered AS MATERIALIZED (SELECT " + HotelRepository.COLUMNS
        + ", description_embedding FROM hotels WHERE description_embedding IS NOT NULL AND "
        + WITHIN_RADIUS + " AND price_per_night <= COALESCE(:maxPrice, price_per_night)) SELECT "
        + HotelRepository.COLUMNS + " FROM filtered "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<HotelEntity> queryHotels(String embedding, double longitude, double latitude,
                                  double radiusMeters, @Nullable Double maxPrice);

    default List<HotelEntity> searchHotelsByVectorNear(Vector embedding, double longitude, double latitude,
                                                       double radiusKm, Double maxPrice) {
        return queryHotels(DuckDbVectors.literal(embedding), longitude, latitude, radiusKm * 1000, maxPrice);
    }

    @Connectable(readOnly = false)
    @Query("WITH filtered AS MATERIALIZED (SELECT " + ActivityRepository.COLUMNS
        + ", description_embedding FROM activities WHERE description_embedding IS NOT NULL AND "
        + WITHIN_RADIUS + ") SELECT " + ActivityRepository.COLUMNS + " FROM filtered "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<ActivityEntity> queryActivities(String embedding, double longitude, double latitude,
                                         double radiusMeters);

    default List<ActivityEntity> searchActivitiesByVectorNear(Vector embedding, double longitude,
                                                               double latitude, double radiusKm) {
        return queryActivities(DuckDbVectors.literal(embedding), longitude, latitude, radiusKm * 1000);
    }
}
