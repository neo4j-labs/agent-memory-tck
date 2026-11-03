package org.neo4j.agentmemory.entity;

import java.util.Optional;
import java.util.UUID;

public record Entity(
        UUID id,
        String name,
        String type,
        String description) {
    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Entity that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
