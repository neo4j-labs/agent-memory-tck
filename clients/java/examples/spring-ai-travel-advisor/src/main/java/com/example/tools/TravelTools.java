package com.example.tools;

import com.example.entity.ActivityEntity;
import com.example.entity.DestinationEntity;
import com.example.entity.GeoPoint;
import com.example.entity.HotelEntity;
import com.example.model.WishlistItem;
import com.example.repository.ActivityRepository;
import com.example.repository.DestinationRepository;
import com.example.repository.HotelRepository;
import com.example.repository.SpatialSearchRepository;
import com.example.repository.WishlistRepository;
import com.example.service.EmbeddingService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
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

    @Tool(description = "Search for Swiss destinations by preference when there is no location constraint. For 'in', 'near', 'around', or 'within km of' requests, use searchNearbyDestinations instead.")
    public String searchDestinations(String query) {
        float[] queryVector = embedding(query);
        List<DestinationEntity> results = destinationRepository.findTop5ByDescriptionEmbeddingNear(queryVector, MAX_COSINE_DISTANCE);
        if (results.isEmpty()) {
            return "No destinations found matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Found destinations:\n");
        for (DestinationEntity d : results) {
            sb.append(String.format("- %s (ID:%d, %s): %s\n", d.name(), d.id(), d.region(), d.description()));
        }
        return sb.toString();
    }

    @Tool(description = "Search for Swiss destinations by preference near a location anchor. Supported anchors: Zermatt, Interlaken, Lucerne, Lausanne, St. Moritz, Lugano, Zurich. radiusKm defaults to 50.")
    public String searchNearbyDestinations(String query, String nearDestinationName, @ToolParam(required = false) Double radiusKm) {
        Optional<GeoPoint> location = locationForDestination(nearDestinationName);
        if (location.isEmpty()) {
            return unsupportedLocation("nearby search", nearDestinationName);
        }

        double radius = radiusOrDefault(radiusKm, DEFAULT_DESTINATION_RADIUS_KM);
        GeoPoint point = location.get();
        float[] queryVector = embedding(query);
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

    @Tool(description = "Search for hotels when there is no location constraint. Optional filters: destinationId, maxPrice (CHF/night); use null or 0 when an optional filter is not specified. For 'in', 'near', 'around', or 'within km of' requests, use searchNearbyHotels instead.")
    public String searchHotels(String query, @ToolParam(required = false) Long destinationId, @ToolParam(required = false) Double maxPrice) {
        float[] queryVector = embedding(query);
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

    @Tool(description = "Search for hotels by preference near a location anchor. Supported anchors: Zermatt, Interlaken, Lucerne, Lausanne, St. Moritz, Lugano, Zurich. Optional maxPrice in CHF/night; use null or 0 when no budget is specified. radiusKm defaults to 15.")
    public String searchNearbyHotels(String query, String nearDestinationName, @ToolParam(required = false) Double radiusKm, @ToolParam(required = false) Double maxPrice) {
        Optional<GeoPoint> location = locationForDestination(nearDestinationName);
        if (location.isEmpty()) {
            return unsupportedLocation("nearby hotel search", nearDestinationName);
        }

        double radius = radiusOrDefault(radiusKm, DEFAULT_HOTEL_RADIUS_KM);
        GeoPoint point = location.get();
        float[] queryVector = embedding(query);
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

    @Tool(description = "Search for activities when there is no location constraint. Optional filter: destinationId. For 'in', 'near', 'around', or 'within km of' requests, use searchNearbyActivities instead.")
    public String searchActivities(String query, @ToolParam(required = false) Long destinationId) {
        float[] queryVector = embedding(query);
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

    @Tool(description = "Search for activities by preference near a location anchor. Supported anchors: Zermatt, Interlaken, Lucerne, Lausanne, St. Moritz, Lugano, Zurich. radiusKm defaults to 40.")
    public String searchNearbyActivities(String query, String nearDestinationName, @ToolParam(required = false) Double radiusKm) {
        Optional<GeoPoint> location = locationForDestination(nearDestinationName);
        if (location.isEmpty()) {
            return unsupportedLocation("nearby activity search", nearDestinationName);
        }

        double radius = radiusOrDefault(radiusKm, DEFAULT_ACTIVITY_RADIUS_KM);
        GeoPoint point = location.get();
        float[] queryVector = embedding(query);
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

    @Tool(description = "Add a specific item to this conversation's wishlist only when the user explicitly asks to add, save, bookmark, or place that item on their wishlist. If the item is ambiguous, ask which one instead of calling this tool. itemType: 'destination', 'hotel', or 'activity'. itemId: from search results.")
    public String addToWishlist(String itemType, Long itemId, ToolContext context) {
        String conversationId = requireConversationId(context.getContext().get("conversationId"));
        String request = String.valueOf(context.getContext().getOrDefault("userMessage", ""))
                .toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'').strip();
        // Accept direct requests, not incidental words in searches (for example, "save money").
        String requestPrefix = "(?:please\\s+|(?:can|could|would|will) you\\s+(?:please\\s+)?|"
                + "i(?:'d| would) like (?:you )?to\\s+|i want (?:you )?to\\s+)?";
        boolean saveVerb = request.matches("(?s)^" + requestPrefix
                + "(?:save|bookmark)\\s+(?!(?:money|time|energy|water)\\b)\\S.+")
                || request.matches("(?s)^" + requestPrefix
                + "(?:add|put|place)\\s+\\S.+\\b(?:wishlist|wish list)\\b.*");
        boolean negated = request.matches("(?s).*\\b(?:not|don't|never|without)\\b.*");
        if (!saveVerb || negated) return "Please ask me to save a specific item to your wishlist.";
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

    @Tool(description = "Get this conversation's wishlist with all saved destinations, hotels, and activities.")
    public String getWishlist(ToolContext context) {
        String conversationId = requireConversationId(context.getContext().get("conversationId"));
        return doGetWishlist(conversationId);
    }

    private String requireConversationId(Object raw) {
        if (!(raw instanceof String id)) throw new IllegalArgumentException("conversationId must be a UUID");
        try {
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
            return uuid.toString();
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException("conversationId must be a UUID", error); }
    }

    private String doGetWishlist(String conversationId) {
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

    private float[] embedding(String query) {
        return embeddingService.generateEmbedding(query);
    }

    private Optional<GeoPoint> locationForDestination(String destinationName) {
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
