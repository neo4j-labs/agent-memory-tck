package org.neo4j.agentmemory.conversation;

import java.util.UUID;

public record Reflection(UUID id, String content) {
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Reflection that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
