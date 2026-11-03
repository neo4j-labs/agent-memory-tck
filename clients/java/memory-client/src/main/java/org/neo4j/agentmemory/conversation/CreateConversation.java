package org.neo4j.agentmemory.conversation;

import java.util.Map;
import java.util.Optional;

/** Creation input; null metadata becomes an immutable empty map, omitted on the wire. */
public record CreateConversation(String userId, Map<String, String> metadata) {
    public CreateConversation() {
        this(null, null);
    }

    public CreateConversation(String userId) {
        this(userId, null);
    }

    public CreateConversation {
        if (userId != null) {
            userId = userId.trim();
            if (userId.isEmpty()) {
                userId = null;
            }
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Optional<String> getUserId() {
        return Optional.ofNullable(userId);
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
