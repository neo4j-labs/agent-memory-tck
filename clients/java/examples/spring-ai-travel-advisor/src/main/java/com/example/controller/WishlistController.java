package com.example.controller;

import com.example.model.WishlistItem;
import com.example.repository.WishlistRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class WishlistController {
    private final WishlistRepository wishlistRepository;

    public WishlistController(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    @GetMapping("/wishlist")
    public List<WishlistItem> getWishlist(@RequestParam(required=false) String conversationId) {
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must be a UUID");
        }
    }
}
