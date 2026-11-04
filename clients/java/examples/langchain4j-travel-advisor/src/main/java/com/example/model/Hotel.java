package com.example.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Hotel(
    Long id,
    Long destinationId,
    String destinationName,
    String name,
    Double pricePerNight,
    String description
) {}
