package com.example.repository;

import com.example.entity.WishlistEntry;
import com.example.model.WishlistItem;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@JdbcRepository(dialect = Dialect.ANSI)
public interface WishlistRepository extends GenericRepository<WishlistEntry, Long> {
    Logger LOG = LoggerFactory.getLogger(WishlistRepository.class);

    @Query("""
        INSERT INTO wishlist_items (conversation_id, item_type, item_id)
        VALUES (:conversationId, :itemType, :itemId) ON CONFLICT DO NOTHING
        """)
    long insertItem(String conversationId, String itemType, Long itemId);

    default boolean save(String conversationId, WishlistItem item) {
        try {
            insertItem(conversationId, item.itemType(), item.itemId());
            return true;
        } catch (RuntimeException e) {
            LOG.error("Error saving wishlist item", e);
            return false;
        }
    }

    // DuckDB does not support Connection.setReadOnly, so open a regular connection for this read.
    @Connectable(readOnly = false)
    @Query("""
        SELECT w.id, w.item_type, w.item_id,
               COALESCE(d.name, h.name, a.name) AS name,
               CASE w.item_type
                   WHEN 'hotel' THEN printf('CHF %.0f / night', COALESCE(h.price_per_night, 0))
                   WHEN 'activity' THEN a.season
                   ELSE d.region
               END AS detail
        FROM wishlist_items w
        LEFT JOIN destinations d ON w.item_type = 'destination' AND d.id = w.item_id
        LEFT JOIN hotels h ON w.item_type = 'hotel' AND h.id = w.item_id
        LEFT JOIN activities a ON w.item_type = 'activity' AND a.id = w.item_id
        WHERE w.conversation_id = :conversationId
        ORDER BY w.id DESC
        """)
    List<WishlistItem> findItems(String conversationId);

    default List<WishlistItem> findAll(String conversationId) {
        try {
            return findItems(conversationId);
        } catch (RuntimeException e) {
            LOG.error("Error finding all wishlist items", e);
            return List.of();
        }
    }

    @Query("DELETE FROM wishlist_items WHERE conversation_id = :conversationId")
    long deleteItems(String conversationId);

    default void deleteAll(String conversationId) {
        try {
            LOG.debug("Deleted {} wishlist items for conversation {}", deleteItems(conversationId), conversationId);
        } catch (RuntimeException e) {
            LOG.error("Error deleting all wishlist items", e);
        }
    }
}
