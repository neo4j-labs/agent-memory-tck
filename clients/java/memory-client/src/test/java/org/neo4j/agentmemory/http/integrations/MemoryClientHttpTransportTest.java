package org.neo4j.agentmemory.http.integrations;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
@ParameterizedClass
@EnumSource(HttpClientUnderTest.class)
class MemoryClientHttpTransportTest {
    private final HttpClientUnderTest httpClient;

    MemoryClientHttpTransportTest(HttpClientUnderTest httpClient) {
        this.httpClient = httpClient;
    }

    @Test
    void userIdIsStrictlyEncodedOnTheWire(WireMockRuntimeInfo wireMock) {
        stubFor(get(urlPathEqualTo("/v1/conversations"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"conversations\":[]}")));

        await(client(wireMock).listConversations(new ListConversations(25, "user one&a+b/ł")));

        assertThat(findAll(getRequestedFor(urlPathEqualTo("/v1/conversations"))))
                .singleElement().satisfies(request -> assertThat(request.getUrl())
                        .isEqualTo("/v1/conversations?limit=25&userId=user%20one%26a%2Bb%2F%C5%82"));
    }

    @ParameterizedTest
    @EnumSource(EncodingMode.class)
    void springEncodingModesPreserveStrictQueryAndTemplate(EncodingMode mode, WireMockRuntimeInfo wireMock) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                httpClient == HttpClientUnderTest.REST_CLIENT || httpClient == HttpClientUnderTest.WEB_CLIENT);
        var factory = new DefaultUriBuilderFactory();
        factory.setEncodingMode(mode);
        var registry = TestObservationRegistry.create();
        var configuration = MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key");
        if (httpClient == HttpClientUnderTest.REST_CLIENT) {
            configuration.restClient(RestClient.builder().uriBuilderFactory(factory)
                    .observationRegistry(registry).requestFactory(new JdkClientHttpRequestFactory()).build(),
                    HttpClientUnderTest.TEST_EXECUTOR);
        } else {
            configuration.webClient(WebClient.builder().uriBuilderFactory(factory)
                    .observationRegistry(registry).build());
        }
        stubFor(get(urlPathEqualTo("/v1/conversations"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"conversations\":[]}")));

        await(MemoryClient.create(configuration.build())
                .listConversations(new ListConversations(25, "user one&a+b/ł")));

        assertThat(findAll(getRequestedFor(urlPathEqualTo("/v1/conversations"))))
                .singleElement().satisfies(request -> assertThat(request.getUrl())
                        .isEqualTo("/v1/conversations?limit=25&userId=user%20one%26a%2Bb%2F%C5%82"));
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("http.client.requests").that()
                .hasLowCardinalityKeyValue("uri", "/v1/conversations?limit={limit}&userId={userId}");
    }

    @Test
    void sendsAuthorizationAcceptAndJsonBody(WireMockRuntimeInfo wireMock) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                          "userId": "alice"
                        }
                        """)));
        var client = client(wireMock);

        await(client.createConversation(new CreateConversation("alice")));

        verify(1, postRequestedFor(urlEqualTo("/v1/conversations"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"userId":"alice"}
                        """)));
    }

    @Test
    void omitsAbsentRecordValuesFromRequests(WireMockRuntimeInfo wireMock) {
        var conversationId = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
        var stepId = UUID.fromString("673139d8-dd48-48eb-a5a9-cc84e6e938e3");
        var callId = UUID.fromString("15c63f73-f00f-45de-b62e-851ea483a552");
        stubFor(post(
                        urlEqualTo("/v1/conversations"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720"
                                }
                                """)));
        stubFor(post(
                        urlEqualTo("/v1/reasoning/steps"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "673139d8-dd48-48eb-a5a9-cc84e6e938e3",
                                  "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                                  "reasoning": "reason",
                                  "actionTaken": "act"
                                }
                                """)));
        stubFor(post(
                        urlEqualTo("/v1/reasoning/tool-calls"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "15c63f73-f00f-45de-b62e-851ea483a552",
                                  "stepId": "673139d8-dd48-48eb-a5a9-cc84e6e938e3",
                                  "toolName": "search",
                                  "status": "success"
                                }
                                """)));
        var client = client(wireMock);

        var conversation = await(client.createConversation(new CreateConversation()));
        var step = await(conversation
                .recordStep(new NewReasoningStep("reason", "act")));
        var call = await(step.recordToolCall(NewToolCall.builder("search", "{}").build()));

        verify(1, postRequestedFor(urlEqualTo("/v1/conversations"))
                .withRequestBody(equalToJson("{}")));
        verify(1, postRequestedFor(urlEqualTo("/v1/reasoning/steps"))
                .withRequestBody(equalToJson("""
                        {
                          "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                          "reasoning": "reason",
                          "actionTaken": "act"
                        }
                        """)));
        verify(1, postRequestedFor(urlEqualTo("/v1/reasoning/tool-calls"))
                .withRequestBody(equalToJson("""
                        {
                          "stepId": "673139d8-dd48-48eb-a5a9-cc84e6e938e3",
                          "toolName": "search",
                          "input": "{}",
                          "status": "success"
                        }
                        """)));

        assertThat(conversation.id()).isEqualTo(conversationId);
        assertThat(step.id()).isEqualTo(stepId);
        assertThat(call.id()).isEqualTo(callId);
    }
    @Test
    void roundTripsNonAsciiContent(WireMockRuntimeInfo wireMock) {
        var id = UUID.randomUUID();
        var content = "Zażółć gęślą jaźń 🧠";
        stubFor(post(
                urlEqualTo("/v1/conversations/" + id + "/messages"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"15c63f73-f00f-45de-b62e-851ea483a552\","
                                + "\"role\":\"user\",\"content\":\"" + content + "\"}")));

        var message = await(client(wireMock).addMessage(id,
                new NewMessage(
                        MessageRole.USER, content)));

        assertThat(message.content()).isEqualTo(content);
        verify(postRequestedFor(urlEqualTo("/v1/conversations/" + id + "/messages"))
                .withRequestBody(equalToJson("{\"role\":\"user\",\"content\":\"" + content + "\"}")));
    }

    @Test
    void serviceFailureKeepsDiagnostics(WireMockRuntimeInfo wireMock) {
        var id = UUID.randomUUID();
        stubFor(get(
                urlEqualTo("/v1/conversations/" + id))
                .willReturn(aResponse().withStatus(503).withHeader("X-Request-ID", "req-503")
                        .withHeader("content-type", "application/problem+json").withBody("{\"detail\":\"busy\"}")));

        var failure = catchThrowable(
                () -> await(client(wireMock).getConversation(id)));
        assertThat(failure).isInstanceOf(MemoryServiceException.class);
        var serviceFailure = (MemoryServiceException) failure;
        assertThat(serviceFailure.statusCode()).isEqualTo(503);
        assertThat(serviceFailure.responseBodyExcerpt()).contains("busy");
        if (httpClient.keepsFailureHeaders()) {
            assertThat(serviceFailure.contentType()).isEqualTo("application/problem+json");
            assertThat(serviceFailure.responseHeaders().keySet())
                    .anyMatch(key -> key.equalsIgnoreCase("x-request-id"));
        } else {
            assertThat(serviceFailure.contentType()).isEmpty();
            assertThat(serviceFailure.responseHeaders()).isEmpty();
        }
    }

    @Test
    void droppedConnectionFailsTheFuture(WireMockRuntimeInfo wireMock) {
        var id = UUID.randomUUID();
        stubFor(get(
                urlEqualTo("/v1/conversations/" + id))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));

        var failure = catchThrowable(
                () -> await(client(wireMock).getConversation(id)));
        assertThat(failure).isInstanceOf(MemoryClientException.class)
                .satisfies(clientFailure -> assertThat(clientFailure.getCause()).isNotNull());
    }

    @Test
    void rejectedExecutorFailsTheFuture(WireMockRuntimeInfo wireMock) {
        org.junit.jupiter.api.Assumptions.assumeTrue(httpClient.takesExecutor());
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var client = MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key"), executor).build());

        var future = client.getConversation(UUID.randomUUID());
        var failure = catchThrowable(() -> await(future));
        assertThat(failure).isInstanceOf(MemoryClientException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        verify(0, anyRequestedFor(anyUrl()));
    }

    private MemoryClient client(WireMockRuntimeInfo wireMock) {
        return MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key")).build());
    }
}
