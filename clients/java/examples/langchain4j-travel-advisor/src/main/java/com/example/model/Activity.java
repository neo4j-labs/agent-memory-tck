package com.example.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Activity(
    Long id,
    Long destinationId,
    String destinationName,
    String name,
    String season,
    String description
) {}
