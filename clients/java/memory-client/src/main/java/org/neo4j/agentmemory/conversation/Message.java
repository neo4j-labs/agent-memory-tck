package org.neo4j.agentmemory.conversation;

import java.util.Map;
import java.util.UUID;

/** Service snapshot with immutable string metadata, empty when absent. */
public record Message(UUID id, MessageRole role, String content, Map<String, String> metadata) {
    public Message(UUID id, MessageRole role, String content) {
        this(id, role, content, Map.of());
    }

    public Message {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Message that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
