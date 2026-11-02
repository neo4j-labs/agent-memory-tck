package com.example.model;


public record Hotel(
    Long id,
    Long destinationId,
    String destinationName,
    String name,
    Double pricePerNight,
    String description
) {}
