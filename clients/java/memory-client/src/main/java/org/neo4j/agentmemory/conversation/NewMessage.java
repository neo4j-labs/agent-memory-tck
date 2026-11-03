package org.neo4j.agentmemory.conversation;

import java.util.Map;

/** Message input with immutable string metadata; null metadata becomes an empty map. */
public record NewMessage(MessageRole role, String content, Map<String, String> metadata) {
    public NewMessage(MessageRole role, String content) {
        this(role, content, Map.of());
    }

    public NewMessage {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static NewMessage user(String content) {
        return new NewMessage(MessageRole.USER, content);
    }
    public static NewMessage assistant(String content) {
        return new NewMessage(MessageRole.ASSISTANT, content);
    }
}
