package org.neo4j.agentmemory.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.http.internal.HttpTransports;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class CustomHttpTransportTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");

    @Test
    void sendsThroughTheApplicationTransport(WireMockRuntimeInfo server) {
        var delegate = HttpTransports.select(HttpClient.newHttpClient());
        var calls = new AtomicInteger();
        var transport = new HttpTransport() {
            public String name() { return "counting-http"; }
            public CompletableFuture<HttpResult> send(HttpCall call) {
                calls.incrementAndGet();
                return delegate.send(call);
            }
        };
        stubFor(get(urlEqualTo("/v1/conversations/" + ID))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));
        var configuration = MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("nams-key")
                .httpTransport(transport).build();

        await(MemoryClient.create(configuration).getConversation(ID));

        assertThat(configuration.httpTransport()).isSameAs(transport);
        assertThat(calls).hasValue(1);
        verify(getRequestedFor(urlEqualTo("/v1/conversations/" + ID))
                .withHeader("Authorization", equalTo("Bearer nams-key")));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "null"})
    void transportFailuresBecomeClientExceptions(String outcome) {
        var original = new IllegalStateException("transport failure");
        var transport = new HttpTransport() {
            public String name() { return "failing-http"; }
            public CompletableFuture<HttpResult> send(HttpCall call) {
                return "failed".equals(outcome) ? CompletableFuture.failedFuture(original) : null;
            }
        };
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl("https://memory.test/v1").apiKey("nams-key").httpTransport(transport).build());

        var failure = catchThrowable(() -> await(client.getConversation(ID)));

        assertThat(failure).isInstanceOf(MemoryClientException.class);
        if ("failed".equals(outcome)) {
            assertThat(failure).hasCause(original);
        } else {
            assertThat(failure).hasCauseInstanceOf(NullPointerException.class);
        }
    }
}
