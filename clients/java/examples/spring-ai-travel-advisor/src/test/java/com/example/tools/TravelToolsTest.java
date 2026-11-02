package com.example.tools;

import com.example.entity.DestinationEntity;
import com.example.entity.HotelEntity;
import com.example.model.WishlistItem;
import com.example.repository.ActivityRepository;
import com.example.repository.DestinationRepository;
import com.example.repository.HotelRepository;
import com.example.repository.SpatialSearchRepository;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelToolsTest {
    private EmbeddingService embeddingService;
    private DestinationRepository destinationRepository;
    private HotelRepository hotelRepository;
    private SpatialSearchRepository spatialSearchRepository;
    private TravelTools tools;
    private WishlistRepository wishlistRepository;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        destinationRepository = mock(DestinationRepository.class);
        hotelRepository = mock(HotelRepository.class);
        spatialSearchRepository = mock(SpatialSearchRepository.class);

        when(embeddingService.generateEmbedding(any())).thenReturn(new float[]{1.0f});
        wishlistRepository = mock(WishlistRepository.class);
        tools = new TravelTools(
            embeddingService,
            destinationRepository,
            hotelRepository,
            mock(ActivityRepository.class),
            spatialSearchRepository,
            wishlistRepository
        );
    }

    @Test
    void refusesSearchOnlyAndNegatedWishlistRequestsBeforeLookup() {
        String id = UUID.randomUUID().toString();
        for (String request : List.of("Find hotels", "Find hotels where I can save money", "Don’t save Zermatt",
                "Don't save Zermatt", "Never add Zermatt", "Search without saving",
                "Show hotels to bookmark later", "Save money on hotels", "I want to save money on hotels",
                "Can you save money in Zurich?",
                "Please do not save Zermatt", "Please don’t add Zermatt to my wishlist")) {
            String result = tools.addToWishlist("destination", 1L,
                new ToolContext(Map.of("conversationId", id, "userMessage", request)));
            Assertions.assertEquals("Please ask me to save a specific item to your wishlist.", result);
        }
        Mockito.verifyNoInteractions(destinationRepository, wishlistRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Save Zermatt", "Please save Zermatt to my wishlist", "Could you please bookmark Zermatt?",
            "Add Zermatt to my wishlist", "I want to add Zermatt to my wishlist",
            "I'd like to add Zermatt to my wishlist", "I would like to add Zermatt to my wishlist",
            "Put Zermatt on my wish list", "Place Zermatt on my wishlist"})
    void savesExplicitRequestsUsingTheCanonicalConversationContext(String request) {
        String id = UUID.randomUUID().toString();
        when(destinationRepository.findById(1L)).thenReturn(Optional.of(
                new DestinationEntity(1L, "Zermatt", "Valais", "Alpine village", 7.7491, 46.0207)));
        when(wishlistRepository.save(eq(id), any())).thenReturn(true);
        String result = tools.addToWishlist("destination", 1L,
                new ToolContext(Map.of(
                        "conversationId", id, "userMessage", request)));
        Assertions.assertEquals("Added to wishlist: Zermatt", result);
        verify(wishlistRepository).save(eq(id), eq(new WishlistItem("destination", 1L)));
    }

    @Test
    void nearbyHotelSearchTreatsZeroMaxPriceAsNoBudgetFilter() {
        when(destinationRepository.findByNameEqualsIgnoreCase("Zermatt")).thenReturn(Optional.of(
            new DestinationEntity(1L, "Zermatt", "Valais", "Alpine village", 7.7491, 46.0207)
        ));
        when(spatialSearchRepository.searchHotelsByVectorNear(
            any(float[].class), eq(7.7491), eq(46.0207), eq(15.0), isNull()
        )).thenReturn(List.of(hotel(1L, "Matterhorn View Hotel")));
        when(destinationRepository.findById(1L)).thenReturn(Optional.of(
            new DestinationEntity(1L, "Zermatt", "Valais", "Alpine village", 7.7491, 46.0207)
        ));

        String result = tools.searchNearbyHotels("recommend hotels", "Zermatt", 15.0, 0.0);

        assertTrue(result.contains("Matterhorn View Hotel"));
        verify(spatialSearchRepository).searchHotelsByVectorNear(
            any(float[].class), eq(7.7491), eq(46.0207), eq(15.0), isNull()
        );
    }

    @Test
    void genericHotelSearchTreatsZeroOptionalValuesAsUnspecified() {
        when(hotelRepository.findTop5ByDescriptionEmbeddingNear(any(float[].class), eq(2.0)))
            .thenReturn(List.of(hotel(7L, "Zurich Old Town Boutique")));

        String result = tools.searchHotels("recommended hotels in Zurich", 0L, 0.0);

        assertTrue(result.contains("Zurich Old Town Boutique"));
        verify(hotelRepository).findTop5ByDescriptionEmbeddingNear(any(float[].class), eq(2.0));
    }

    private HotelEntity hotel(Long destinationId, String name) {
        return new HotelEntity(10L, destinationId, name, 240.0, "A comfortable hotel", null, null);
    }
}
