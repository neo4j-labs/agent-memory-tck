package org.neo4j.agentmemory.testsupport;

import org.neo4j.agentmemory.MemoryClient;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class TestAwaitSupport {

    public static <T> T await(CompletableFuture<T> future) {
        return MemoryClient.await(future, Duration.ofSeconds(2));
    }
}
