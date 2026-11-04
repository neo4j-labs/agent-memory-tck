package com.example.tools;

import com.example.entity.DestinationEntity;
import com.example.entity.HotelEntity;
import com.example.repository.ActivityRepository;
import com.example.repository.DestinationRepository;
import com.example.repository.HotelRepository;
import com.example.repository.SpatialSearchRepository;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import io.micronaut.data.model.vector.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        destinationRepository = mock(DestinationRepository.class);
        hotelRepository = mock(HotelRepository.class);
        spatialSearchRepository = mock(SpatialSearchRepository.class);

        when(embeddingService.generateEmbedding(any())).thenReturn(new float[]{1.0f});
        tools = new TravelTools(
            embeddingService,
            destinationRepository,
            hotelRepository,
            mock(ActivityRepository.class),
            spatialSearchRepository,
            mock(WishlistRepository.class)
        );
    }

    @Test
    void nearbyHotelSearchTreatsZeroMaxPriceAsNoBudgetFilter() {
        when(destinationRepository.findByNameEqualsIgnoreCase("Zermatt")).thenReturn(Optional.of(
            new DestinationEntity(1L, "Zermatt", "Valais", "Alpine village", 7.7491, 46.0207)
        ));
        when(spatialSearchRepository.searchHotelsByVectorNear(
            any(Vector.class), eq(7.7491), eq(46.0207), eq(15.0), isNull()
        )).thenReturn(List.of(hotel(1L, "Matterhorn View Hotel")));
        when(destinationRepository.findById(1L)).thenReturn(Optional.of(
            new DestinationEntity(1L, "Zermatt", "Valais", "Alpine village", 7.7491, 46.0207)
        ));

        String result = tools.searchNearbyHotels("recommend hotels", "Zermatt", 15.0, 0.0);

        assertTrue(result.contains("Matterhorn View Hotel"));
        verify(spatialSearchRepository).searchHotelsByVectorNear(
            any(Vector.class), eq(7.7491), eq(46.0207), eq(15.0), isNull()
        );
    }

    @Test
    void genericHotelSearchTreatsZeroOptionalValuesAsUnspecified() {
        when(hotelRepository.findTop5ByDescriptionEmbeddingNear(any(Vector.class), eq(2.0)))
            .thenReturn(List.of(hotel(7L, "Zurich Old Town Boutique")));

        String result = tools.searchHotels("recommended hotels in Zurich", 0L, 0.0);

        assertTrue(result.contains("Zurich Old Town Boutique"));
        verify(hotelRepository).findTop5ByDescriptionEmbeddingNear(any(Vector.class), eq(2.0));
    }

    private HotelEntity hotel(Long destinationId, String name) {
        return new HotelEntity(10L, destinationId, name, 240.0, "A comfortable hotel", null, null);
    }
}
