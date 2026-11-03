package org.neo4j.agentmemory.user;

/** The outcome of one service call deleting a user's User Memory. */
public record UserMemoryDeletion(String userId, boolean complete) {}
