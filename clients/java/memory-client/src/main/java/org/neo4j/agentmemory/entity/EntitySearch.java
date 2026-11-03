package org.neo4j.agentmemory.entity;

public record EntitySearch(String query, String type, int limit) {
    private static final int DEFAULT_LIMIT = 10;

    public EntitySearch(String query) {
        this(query, null, DEFAULT_LIMIT);
    }

    public EntitySearch(String query, int limit) {
        this(query, null, limit);
    }

    public EntitySearch(String query, String type) {
        this(query, type, DEFAULT_LIMIT);
    }
}
