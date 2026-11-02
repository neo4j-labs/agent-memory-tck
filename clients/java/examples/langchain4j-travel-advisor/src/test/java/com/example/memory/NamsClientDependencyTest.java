package com.example.memory;

import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NamsClientDependencyTest {
    @Test void clientIsAvailable() {
        assertNotNull(MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl("http://localhost:18080/v1")
            .apiKey("test-key")
            .build()));
    }
}
