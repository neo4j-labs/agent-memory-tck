package org.neo4j.agentmemory.conformance;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Binds TCK Sessions to hosted Conversations; used by the serial bridge executor. */
final class Sessions {
    static final String USER_ID = "tck-java";
    static final String METADATA_KEY = "tck.session";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    private final MemoryClient client;
    private final Map<String, Conversation> cache = new HashMap<>();

    Sessions(MemoryClient client) {
        this.client = client;
    }

    Optional<Conversation> find(String name) {
        var cached = cache.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        var found = MemoryClient.await(
                        client.listConversations(new ListConversations(200, USER_ID)), AWAIT_TIMEOUT)
                .stream().filter(c -> name.equals(c.metadata().get(METADATA_KEY))).findFirst();
        found.ifPresent(c -> cache.put(name, c));
        return found;
    }

    Conversation bind(String name) {
        return find(name).orElseGet(() -> {
            var conversation = MemoryClient.await(client.createConversation(
                    new CreateConversation(USER_ID, Map.of(METADATA_KEY, name))), AWAIT_TIMEOUT);
            cache.put(name, conversation);
            return conversation;
        });
    }

    void clear(String name) {
        find(name).ifPresent(conversation -> {
            MemoryClient.await(client.deleteConversation(conversation.id()), AWAIT_TIMEOUT);
            cache.remove(name);
        });
    }

    List<Conversation> all(int limit) {
        return MemoryClient.await(
                        client.listConversations(new ListConversations(limit, USER_ID)), AWAIT_TIMEOUT)
                .stream().filter(c -> c.metadata().containsKey(METADATA_KEY)).toList();
    }

    void forgetAll() {
        for (int call = 0; call < 10; call++) {
            if (MemoryClient.await(client.deleteUserMemory(USER_ID), AWAIT_TIMEOUT).complete()) {
                cache.clear();
                return;
            }
        }
        throw new IllegalStateException("clear_all_data still incomplete after 10 calls");
    }
}
