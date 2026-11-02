package com.example.model;


public record Activity(
    Long id,
    Long destinationId,
    String destinationName,
    String name,
    String season,
    String description
) {}
