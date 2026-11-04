package com.example.controller;

import com.example.model.WishlistItem;
import com.example.repository.WishlistRepository;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.UUID;

@Controller("/api")
public class WishlistController {
    private final WishlistRepository wishlistRepository;

    public WishlistController(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    @Get("/wishlist")
    public List<WishlistItem> getWishlist(@Nullable @QueryValue String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return wishlistRepository.findAll(requireConversationId(conversationId));
    }

    private String requireConversationId(String conversationId) {
        try {
            UUID id = UUID.fromString(conversationId);
            if (!id.toString().equalsIgnoreCase(conversationId)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return id.toString();
        } catch (IllegalArgumentException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "conversationId must be a UUID");
        }
    }
}
