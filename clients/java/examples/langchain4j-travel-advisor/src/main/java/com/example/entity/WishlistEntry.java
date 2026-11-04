package com.example.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity("wishlist_items")
public record WishlistEntry(@Id Long id, String conversationId, String itemType, Long itemId) {
}
