package com.example.repository;

import com.example.model.WishlistItem;
import io.micronaut.context.ApplicationContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbWishlistRepositoryTest {
    @Test
    void scopesDeduplicationAndDisplayFieldsToConversation() throws Exception {
        String url = "jdbc:duckdb:memory:wishlist_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection ignored = DriverManager.getConnection(url, "", "")) {
            Flyway.configure().dataSource(url, "", "").locations("classpath:db/duckdb").load().migrate();
            try (ApplicationContext context = RepositoryTestContext.start(url)) {
                WishlistRepository wishlist = context.getBean(WishlistRepository.class);
                String first = UUID.randomUUID().toString();
                String second = UUID.randomUUID().toString();
                WishlistItem hotel = new WishlistItem(0L, "hotel", 1L, null, null);
                assertTrue(wishlist.save(first, hotel));
                assertTrue(wishlist.save(first, hotel));
                assertTrue(wishlist.save(second, hotel));
                assertTrue(wishlist.save(first, new WishlistItem("destination", 1L)));
                assertTrue(wishlist.save(first, new WishlistItem("activity", 1L)));
                List<WishlistItem> items = wishlist.findAll(first);
                assertEquals(3, items.size());
                assertEquals(1, wishlist.findAll(second).size());
                assertTrue(items.stream().anyMatch(i -> i.itemType().equals("hotel")
                    && i.name().equals("Matterhorn View Hotel") && i.detail().equals("CHF 280 / night")));
                assertTrue(items.stream().anyMatch(i -> i.itemType().equals("destination")
                    && i.name().equals("Zermatt") && i.detail().equals("Valais")));
                assertTrue(items.stream().anyMatch(i -> i.itemType().equals("activity")
                    && i.name().equals("Gornergrat Railway Excursion") && i.detail().equals("All Year")));
                wishlist.deleteAll(first);
                assertEquals(List.of(), wishlist.findAll(first));
                assertEquals(1, wishlist.findAll(second).size());
            }
        }
    }
}
