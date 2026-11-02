package com.example.repository;

import com.example.entity.ActivityEntity;
import com.example.entity.DestinationEntity;
import com.example.entity.HotelEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface SpatialSearchRepository extends Repository<DestinationEntity, Long> {
    String WITHIN_RADIUS = "ST_DWithin_Spheroid(ST_Point2D(longitude, latitude), "
        + "ST_Point2D(:longitude, :latitude), :radiusMeters)";

    @Query("WITH filtered AS MATERIALIZED (SELECT " + DestinationRepository.COLUMNS
        + ", description_embedding FROM destinations WHERE description_embedding IS NOT NULL AND "
        + WITHIN_RADIUS + ") SELECT " + DestinationRepository.COLUMNS + " FROM filtered "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<DestinationEntity> queryDestinations(@Param("embedding") String embedding, @Param("longitude") double longitude, @Param("latitude") double latitude,
                                               @Param("radiusMeters") double radiusMeters);

    default List<DestinationEntity> searchDestinationsByVectorNear(float[] embedding, double longitude,
                                                                    double latitude, double radiusKm) {
        return queryDestinations(DuckDbVectors.literal(embedding), longitude, latitude, radiusKm * 1000);
    }

    @Query("WITH filtered AS MATERIALIZED (SELECT " + HotelRepository.COLUMNS
        + ", description_embedding FROM hotels WHERE description_embedding IS NOT NULL AND "
        + WITHIN_RADIUS + " AND price_per_night <= COALESCE(:maxPrice, price_per_night)) SELECT "
        + HotelRepository.COLUMNS + " FROM filtered "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<HotelEntity> queryHotels(@Param("embedding") String embedding, @Param("longitude") double longitude, @Param("latitude") double latitude,
                                  @Param("radiusMeters") double radiusMeters, @Param("maxPrice") Double maxPrice);

    default List<HotelEntity> searchHotelsByVectorNear(float[] embedding, double longitude, double latitude,
                                                       double radiusKm, Double maxPrice) {
        return queryHotels(DuckDbVectors.literal(embedding), longitude, latitude, radiusKm * 1000, maxPrice);
    }

    @Query("WITH filtered AS MATERIALIZED (SELECT " + ActivityRepository.COLUMNS
        + ", description_embedding FROM activities WHERE description_embedding IS NOT NULL AND "
        + WITHIN_RADIUS + ") SELECT " + ActivityRepository.COLUMNS + " FROM filtered "
        + "ORDER BY array_cosine_distance(description_embedding, CAST(:embedding AS FLOAT[1536])) LIMIT 5")
    List<ActivityEntity> queryActivities(@Param("embedding") String embedding, @Param("longitude") double longitude, @Param("latitude") double latitude,
                                         @Param("radiusMeters") double radiusMeters);

    default List<ActivityEntity> searchActivitiesByVectorNear(float[] embedding, double longitude,
                                                               double latitude, double radiusKm) {
        return queryActivities(DuckDbVectors.literal(embedding), longitude, latitude, radiusKm * 1000);
    }
}
