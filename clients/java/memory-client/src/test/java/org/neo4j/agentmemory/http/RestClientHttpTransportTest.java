package org.neo4j.agentmemory.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.testsupport.HttpClientUnderTest;
import org.neo4j.agentmemory.testsupport.OpenApiContract;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class RestClientHttpTransportTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final String PATH = "/v1/conversations/" + ID;

    @Test
    void observationsTagTheRouteTemplate(WireMockRuntimeInfo server) {
        var registry = TestObservationRegistry.create();
        var restClient = builder().observationRegistry(registry).build();
        stubConversation();

        await(client(server, restClient).getConversation(ID));

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("http.client.requests").that()
                .hasLowCardinalityKeyValue("uri", "/v1/conversations/{conversationId}");
        assertContract();
    }

    @Test
    void runsApplicationInterceptors(WireMockRuntimeInfo server) {
        var restClient = builder().requestInterceptor((request, body, execution) -> {
            request.getHeaders().set("X-App-Trace", "trace-1");
            return execution.execute(request, body);
        }).build();
        stubConversation();

        await(client(server, restClient).getConversation(ID));

        verify(getRequestedFor(urlEqualTo(PATH)).withHeader("X-App-Trace", equalTo("trace-1")));
        assertContract();
    }

    @Test
    void appliesApplicationDefaultsWhileMemoryHeadersWin(WireMockRuntimeInfo server) {
        var restClient = builder().baseUrl("http://wrong.invalid/api")
                .defaultHeader("Authorization", "Bearer app-token")
                .defaultHeader("Accept", "application/xml")
                .defaultHeader("X-App-Tenant", "tenant-1")
                .defaultCookie("SESSION", "app-session")
                .defaultRequest(request -> request.header("X-App-Request", "request-1")
                        .header("Authorization", "Bearer app-request-token")).build();
        stubConversation();

        await(client(server, restClient).getConversation(ID));

        verify(getRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer nams-key"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-App-Tenant", equalTo("tenant-1"))
                .withHeader("X-App-Request", equalTo("request-1"))
                .withCookie("SESSION", equalTo("app-session")));
    }

    @Test
    void bypassesRestClientStatusHandlers(WireMockRuntimeInfo server) {
        var restClient = builder().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new IllegalStateException("application status handler");
        }).build();
        stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)
                .withHeader("X-Request-ID", "req-503")));

        var failure = catchThrowable(() -> await(client(server, restClient).getConversation(ID)));

        assertThat(failure).isInstanceOf(MemoryServiceException.class);
        var serviceFailure = (MemoryServiceException) failure;
        assertThat(serviceFailure.statusCode()).isEqualTo(503);
        assertThat(serviceFailure.responseHeaders().keySet())
                .anyMatch(name -> name.equalsIgnoreCase("x-request-id"));
    }

    private static RestClient.Builder builder() {
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory());
    }

    private static MemoryClient client(WireMockRuntimeInfo server, RestClient restClient) {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("nams-key")
                .restClient(restClient, HttpClientUnderTest.TEST_EXECUTOR).build());
    }

    private static void stubConversation() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));
    }

    private static void assertContract() {
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }
}
