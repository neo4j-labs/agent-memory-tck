package org.neo4j.agentmemory.conversation;

import java.util.List;

public record ConversationContext(
        List<Reflection> reflections,
        List<Observation> observations,
        List<Message> recentMessages) {
    public ConversationContext {
        reflections = immutable(reflections);
        observations = immutable(observations);
        recentMessages = immutable(recentMessages);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
