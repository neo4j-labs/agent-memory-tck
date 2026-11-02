package com.example.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

@Table("hotels")
public record HotelEntity(@Id Long id, @Column("destination_id") Long destinationId, String name, @Column("price_per_night") Double pricePerNight,
                          String description, Double longitude, Double latitude) {
    public GeoPoint location() {
        return longitude == null || latitude == null ? null : new GeoPoint(longitude, latitude);
    }
}
