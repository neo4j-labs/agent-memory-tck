package com.example.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Transient;
import io.micronaut.data.model.geo.Point;

@MappedEntity("destinations")
public record DestinationEntity(@Id Long id, String name, String region, String description,
                                Double longitude, Double latitude) {
    @Transient
    public Point location() {
        return longitude == null || latitude == null ? null : new Point(longitude, latitude);
    }
}
