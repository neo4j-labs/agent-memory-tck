package org.neo4j.agentmemory.http.integrations;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.http.JsonCodecUnderTest;
import org.neo4j.agentmemory.testsupport.OpenApiContract;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reruns every {@link MemoryClientContractTest} operation against the contract the configured
 * service publishes today, so an upstream change fails here only when it breaks this client.
 *
 * <p>
 * Maven Failsafe discovers it during {@code verify}. Like {@code HostedServiceIT}, every operation
 * is marked skipped when {@code MEMORY_API_KEY} is absent or blank; it is also skipped when the
 * published contract cannot be fetched, and fails when the fetched document is not a contract.
 * Skips are taken per operation because a class-level abort is not reported as skipped.
 */
class LiveContractIT extends MemoryClientContractTest {
    private static String skipReason;

    LiveContractIT(HttpClientUnderTest httpClient, JsonCodecUnderTest codec) {
        super(httpClient, codec);
    }

    @BeforeAll
    static void usePublishedContract() throws InterruptedException {
        var apiKey = System.getenv("MEMORY_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            skipReason = "MEMORY_API_KEY is not set";
            return;
        }
        var service = MemoryClientConfiguration.builder().build().baseUrl();
        try {
            OpenApiContract.use(OpenApiContract.published(service.resolve("/openapi.json")));
        } catch (TestAbortedException unavailable) {
            skipReason = unavailable.getMessage();
        }
    }

    @BeforeEach
    void requirePublishedContract() {
        assumeTrue(skipReason == null, skipReason);
    }
}
