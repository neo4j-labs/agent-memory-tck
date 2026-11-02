package com.example.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("destinations")
public record DestinationEntity(@Id Long id, String name, String region, String description,
                                Double longitude, Double latitude) {
    public GeoPoint location() {
        return longitude == null || latitude == null ? null : new GeoPoint(longitude, latitude);
    }
}
