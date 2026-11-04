package com.example.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Destination(
    Long id,
    String name,
    String region,
    String description
) {}
