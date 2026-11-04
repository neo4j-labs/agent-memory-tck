package com.example.memory;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.net.URI;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;

/** Validates runtime settings before constructing the client. */
@Factory
public class NamsClientFactory {
    @Singleton
    public MemoryClient memoryClient(NamsMemoryConfiguration configuration) {
        URI endpoint;
        try { endpoint = URI.create(configuration.getBaseUrl()); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid NAMS endpoint"); }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || !"/v1".equals(endpoint.getPath())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("NAMS endpoint (" + endpoint + ") must be an HTTP(S) /v1 URL");
        }
        if (configuration.getApiKey() == null || configuration.getApiKey().isBlank()) {
            throw new IllegalArgumentException("NAMS API key is required");
        }
        if (configuration.getWaitTimeout() == null || configuration.getWaitTimeout().toMillis() <= 0) {
            throw new IllegalArgumentException("NAMS wait timeout must be positive");
        }
        return MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(endpoint.toString())
            .apiKey(configuration.getApiKey())
            .build());
    }
}
