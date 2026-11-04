package com.example.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

@Table("wishlist_items")
public record WishlistEntry(@Id Long id, @Column("conversation_id") String conversationId, @Column("item_type") String itemType, @Column("item_id") Long itemId) {
}
