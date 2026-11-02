package com.example.tools;

import com.example.entity.ActivityEntity;
import com.example.entity.DestinationEntity;
import com.example.entity.HotelEntity;
import com.example.model.WishlistItem;
import com.example.repository.ActivityRepository;
import com.example.repository.DestinationRepository;
import com.example.repository.HotelRepository;
import com.example.repository.SpatialSearchRepository;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import io.micronaut.data.model.geo.Point;
import io.micronaut.data.model.vector.FloatVector;
import io.micronaut.data.model.vector.Vector;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Singleton
public class TravelTools {
    private static final double MAX_COSINE_DISTANCE = 2.0;
    private static final double DEFAULT_DESTINATION_RADIUS_KM = 50.0;
    private static final double DEFAULT_HOTEL_RADIUS_KM = 15.0;
    private static final double DEFAULT_ACTIVITY_RADIUS_KM = 40.0;

    private final EmbeddingService embeddingService;
    private final DestinationRepository destinationRepository;
    private final HotelRepository hotelRepository;
    private final ActivityRepository activityRepository;
    private final SpatialSearchRepository spatialSearchRepository;
    private final WishlistRepository wishlistRepository;

    public TravelTools(
        EmbeddingService embeddingService,
        DestinationRepository destinationRepository,
        HotelRepository hotelRepository,
        ActivityRepository activityRepository,
        SpatialSearchRepository spatialSearchRepository,
        WishlistRepository wishlistRepository
    ) {
        this.embeddingService = embeddingService;
        this.destinationRepository = destinationRepository;
        this.hotelRepository = hotelRepository;
        this.activityRepository = activityRepository;
        this.spatialSearchRepository = spatialSearchRepository;
        this.wishlistRepository = wishlistRepository;
    }

    @Tool("Search for Swiss destinations by preference when there is no location constraint. For 'in', 'near', 'around', or 'within km of' requests, use searchNearbyDestinations instead.")
    public String searchDestinations(String query) {
        Vector queryVector = embedding(query);
        List<DestinationEntity> results =
                destinationRepository.findTop5ByDescriptionEmbeddingNear(queryVector, MAX_COSINE_DISTANCE);
        if (results.isEmpty()) {
            return "No destinations found matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found destinations:\n");
        for (DestinationEntity d : results) {
            sb.append(String.format("- %s (ID:%d, %s): %s\n", d.name(), d.id(), d.region(), d.description()));
        }
        return sb.toString();
    }

    @Tool("Search for Swiss destinations by preference near a location anchor. Supported anchors: Zermatt, Interlaken, Lucerne, Lausanne, St. Moritz, Lugano, Zurich. radiusKm defaults to 50.")
    public String searchNearbyDestinations(String query, String nearDestinationName, Double radiusKm) {
        Optional<Point> location = locationForDestination(nearDestinationName);
        if (location.isEmpty()) {
            return unsupportedLocation("nearby search", nearDestinationName);
        }

        double radius = radiusOrDefault(radiusKm, DEFAULT_DESTINATION_RADIUS_KM);
        Point point = location.get();
        Vector queryVector = embedding(query);
        List<DestinationEntity> results = spatialSearchRepository.searchDestinationsByVectorNear(
                queryVector, point.x(), point.y(), radius);

        if (results.isEmpty()) {
            return "No destinations found within " + radius + " km of " + nearDestinationName + " matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found nearby destinations:\n");
        for (DestinationEntity d : results) {
            sb.append(String.format("- %s (ID:%d, %s): %s\n", d.name(), d.id(), d.region(), d.description()));
        }
        return sb.toString();
    }

    @Tool("Search for hotels when there is no location constraint. Optional filters: destinationId, maxPrice (CHF/night); use null or 0 when an optional filter is not specified. For 'in', 'near', 'around', or 'within km of' requests, use searchNearbyHotels instead.")
    public String searchHotels(String query, Long destinationId, Double maxPrice) {
        Vector queryVector = embedding(query);
        Long effectiveDestinationId = positiveOrNull(destinationId);
        Double effectiveMaxPrice = positiveOrNull(maxPrice);
        List<HotelEntity> results;
        if (effectiveDestinationId != null && effectiveMaxPrice != null) {
            results = hotelRepository.findTop5ByDestinationIdAndPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(
                    effectiveDestinationId, effectiveMaxPrice, queryVector, MAX_COSINE_DISTANCE);
        } else if (effectiveDestinationId != null) {
            results = hotelRepository.findTop5ByDestinationIdAndDescriptionEmbeddingNear(
                    effectiveDestinationId, queryVector, MAX_COSINE_DISTANCE);
        } else if (effectiveMaxPrice != null) {
            results = hotelRepository.findTop5ByPricePerNightLessThanEqualsAndDescriptionEmbeddingNear(
                    effectiveMaxPrice, queryVector, MAX_COSINE_DISTANCE);
        } else {
            results = hotelRepository.findTop5ByDescriptionEmbeddingNear(queryVector, MAX_COSINE_DISTANCE);
        }
        if (results.isEmpty()) {
            return "No hotels found matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found hotels:\n");
        for (HotelEntity h : results) {
            sb.append(String.format("- %s (ID:%d, CHF %.0f/night): %s\n", h.name(), h.id(), h.pricePerNight(), h.description()));
        }
        return sb.toString();
    }

    @Tool("Search for hotels by preference near a location anchor. Supported anchors: Zermatt, Interlaken, Lucerne, Lausanne, St. Moritz, Lugano, Zurich. Optional maxPrice in CHF/night; use null or 0 when no budget is specified. radiusKm defaults to 15.")
    public String searchNearbyHotels(String query, String nearDestinationName, Double radiusKm, Double maxPrice) {
        Optional<Point> location = locationForDestination(nearDestinationName);
        if (location.isEmpty()) {
            return unsupportedLocation("nearby hotel search", nearDestinationName);
        }

        double radius = radiusOrDefault(radiusKm, DEFAULT_HOTEL_RADIUS_KM);
        Point point = location.get();
        Vector queryVector = embedding(query);
        Double effectiveMaxPrice = positiveOrNull(maxPrice);
        List<HotelEntity> results = spatialSearchRepository.searchHotelsByVectorNear(
                queryVector, point.x(), point.y(), radius, effectiveMaxPrice);

        if (results.isEmpty()) {
            return "No hotels found within " + radius + " km of " + nearDestinationName + " matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found nearby hotels:\n");
        for (HotelEntity h : results) {
            sb.append(String.format("- %s (ID:%d, %s, CHF %.0f/night): %s\n",
                h.name(),
                h.id(),
                destinationName(h.destinationId()),
                h.pricePerNight(),
                h.description()
            ));
        }
        return sb.toString();
    }

    @Tool("Search for activities when there is no location constraint. Optional filter: destinationId. For 'in', 'near', 'around', or 'within km of' requests, use searchNearbyActivities instead.")
    public String searchActivities(String query, Long destinationId) {
        Vector queryVector = embedding(query);
        Long effectiveDestinationId = positiveOrNull(destinationId);
        List<ActivityEntity> results = effectiveDestinationId == null
                ? activityRepository.findTop5ByDescriptionEmbeddingNear(queryVector, MAX_COSINE_DISTANCE)
                : activityRepository.findTop5ByDestinationIdAndDescriptionEmbeddingNear(
                        effectiveDestinationId, queryVector, MAX_COSINE_DISTANCE);
        if (results.isEmpty()) {
            return "No activities found matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found activities:\n");
        for (ActivityEntity a : results) {
            sb.append(String.format("- %s (ID:%d, %s): %s\n", a.name(), a.id(), a.season(), a.description()));
        }
        return sb.toString();
    }

    @Tool("Search for activities by preference near a location anchor. Supported anchors: Zermatt, Interlaken, Lucerne, Lausanne, St. Moritz, Lugano, Zurich. radiusKm defaults to 40.")
    public String searchNearbyActivities(String query, String nearDestinationName, Double radiusKm) {
        Optional<Point> location = locationForDestination(nearDestinationName);
        if (location.isEmpty()) {
            return unsupportedLocation("nearby activity search", nearDestinationName);
        }

        double radius = radiusOrDefault(radiusKm, DEFAULT_ACTIVITY_RADIUS_KM);
        Point point = location.get();
        Vector queryVector = embedding(query);
        List<ActivityEntity> results = spatialSearchRepository.searchActivitiesByVectorNear(
                queryVector, point.x(), point.y(), radius);

        if (results.isEmpty()) {
            return "No activities found within " + radius + " km of " + nearDestinationName + " matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found nearby activities:\n");
        for (ActivityEntity a : results) {
            sb.append(String.format("- %s (ID:%d, %s, %s): %s\n",
                a.name(),
                a.id(),
                destinationName(a.destinationId()),
                a.season(),
                a.description()
            ));
        }
        return sb.toString();
    }

    @Tool("Add a specific item to this conversation's wishlist only when the user explicitly asks to add, save, bookmark, or place that item on their wishlist. If the item is ambiguous, ask which one instead of calling this tool. itemType: 'destination', 'hotel', or 'activity'. itemId: from search results.")
    public String addToWishlist(@ToolMemoryId String conversationId, String itemType, Long itemId) {
        String type = itemType.toLowerCase();
        String name = switch (type) {
            case "destination" -> {
                Optional<DestinationEntity> d = destinationRepository.findById(itemId);
                yield d.map(DestinationEntity::name).orElse(null);
            }
            case "hotel" -> {
                Optional<HotelEntity> h = hotelRepository.findById(itemId);
                yield h.map(HotelEntity::name).orElse(null);
            }
            case "activity" -> {
                Optional<ActivityEntity> a = activityRepository.findById(itemId);
                yield a.map(ActivityEntity::name).orElse(null);
            }
            default -> null;
        };
        if (name == null) {
            return "Error: " + itemType + " with ID " + itemId + " not found.";
        }
        if (!wishlistRepository.save(conversationId, new WishlistItem(type, itemId))) {
            return "Error: could not save " + name + " to the wishlist.";
        }
        return "Added to wishlist: " + name;
    }

    @Tool("Get this conversation's wishlist with all saved destinations, hotels, and activities.")
    public String getWishlist(@ToolMemoryId String conversationId) {
        List<WishlistItem> items = wishlistRepository.findAll(conversationId);
        if (items.isEmpty()) {
            return "Your wishlist is empty.";
        }
        StringBuilder sb = new StringBuilder("Your wishlist:\n");
        for (WishlistItem item : items) {
            String detail = switch (item.itemType()) {
                case "destination" -> {
                    Optional<DestinationEntity> d = destinationRepository.findById(item.itemId());
                    yield d.map(destination -> destination.name() + " (" + destination.region() + ")").orElse("Unknown destination");
                }
                case "hotel" -> {
                    Optional<HotelEntity> h = hotelRepository.findById(item.itemId());
                    yield h.map(hotel -> hotel.name() + " - CHF " + hotel.pricePerNight() + "/night").orElse("Unknown hotel");
                }
                case "activity" -> {
                    Optional<ActivityEntity> a = activityRepository.findById(item.itemId());
                    yield a.map(activity -> activity.name() + " (" + activity.season() + ")").orElse("Unknown activity");
                }
                default -> "Unknown item";
            };
            sb.append("- ").append(detail).append("\n");
        }
        return sb.toString();
    }

    private Vector embedding(String query) {
        return new FloatVector(embeddingService.generateEmbedding(query));
    }

    private Optional<Point> locationForDestination(String destinationName) {
        if (destinationName == null || destinationName.isBlank()) {
            return Optional.empty();
        }
        Optional<DestinationEntity> exact = destinationRepository.findByNameEqualsIgnoreCase(destinationName);
        if (exact.isPresent()) {
            return Optional.ofNullable(exact.get().location());
        }

        String normalized = destinationName.toLowerCase(Locale.ROOT);
        return destinationRepository.findAll()
            .stream()
            .filter(destination -> destination.name().toLowerCase(Locale.ROOT).contains(normalized))
            .map(DestinationEntity::location)
            .filter(point -> point != null)
            .findFirst();
    }

    private String destinationName(Long destinationId) {
        return destinationRepository.findById(destinationId)
            .map(DestinationEntity::name)
            .orElse("Unknown destination");
    }

    private String unsupportedLocation(String searchType, String locationName) {
        return "Unknown destination for " + searchType + ": " + locationName
            + ". Supported location anchors: " + supportedLocationAnchors() + ".";
    }

    private String supportedLocationAnchors() {
        return destinationRepository.findAll()
            .stream()
            .map(DestinationEntity::name)
            .sorted()
            .reduce((left, right) -> left + ", " + right)
            .orElse("none");
    }

    private double radiusOrDefault(Double radiusKm, double defaultRadiusKm) {
        if (radiusKm == null || radiusKm <= 0) {
            return defaultRadiusKm;
        }
        return radiusKm;
    }

    private Long positiveOrNull(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private Double positiveOrNull(Double value) {
        return value == null || value <= 0 ? null : value;
    }

}
