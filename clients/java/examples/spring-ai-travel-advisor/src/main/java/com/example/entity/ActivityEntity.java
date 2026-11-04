package com.example.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

@Table("activities")
public record ActivityEntity(@Id Long id, @Column("destination_id") Long destinationId, String name, String season, String description,
                             Double longitude, Double latitude) {
    public GeoPoint location() {
        return longitude == null || latitude == null ? null : new GeoPoint(longitude, latitude);
    }
}
