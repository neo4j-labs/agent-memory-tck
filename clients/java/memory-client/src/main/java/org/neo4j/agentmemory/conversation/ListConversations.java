package org.neo4j.agentmemory.conversation;

import java.util.Optional;

/** Listing input; a null or blank user ID leaves Conversations unfiltered. */
public record ListConversations(int limit, String userId) {
    public ListConversations(int limit) {
        this(limit, null);
    }

    public ListConversations {
        if (userId != null) {
            userId = userId.trim();
            if (userId.isEmpty()) {
                userId = null;
            }
        }
    }

    public Optional<String> getUserId() {
        return Optional.ofNullable(userId);
    }
}
