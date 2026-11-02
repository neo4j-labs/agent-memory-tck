package com.example.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record WishlistItem(Long id, String itemType, Long itemId, String name, String detail) {

    public WishlistItem(String itemType, Long itemId) {
        this(null, itemType, itemId, null, null);
    }

    public WishlistItem(Long id, String itemType, Long itemId) {
        this(id, itemType, itemId, null, null);
    }
}
