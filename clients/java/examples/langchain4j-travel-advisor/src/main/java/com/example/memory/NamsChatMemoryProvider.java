package com.example.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import io.micronaut.context.annotation.Primary;
import jakarta.inject.Singleton;
import java.util.UUID;
import org.neo4j.agentmemory.MemoryClient;
import static org.neo4j.agentmemory.MemoryClient.await;

@Singleton
@Primary
public final class NamsChatMemoryProvider implements ChatMemoryProvider {
    private final MemoryClient client;
    private final NamsMemoryConfiguration configuration;

    public NamsChatMemoryProvider(MemoryClient client, NamsMemoryConfiguration configuration) {
        this.client = client;
        this.configuration = configuration;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        UUID id = ConversationIds.parse(memoryId);
        return new NamsChatMemory(
            await(client.getConversation(id), configuration.getWaitTimeout()), configuration);
    }
}
