package org.neo4j.agentmemory.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.http.internal.HttpTransports;
import org.neo4j.agentmemory.testsupport.HttpClientUnderTest;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class LangChain4jHttpTransportTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final String PATH = "/v1/conversations/" + ID;

    @Test
    void usesTheApplicationHttpClientWithoutABodyForGet(WireMockRuntimeInfo server) {
        var delegate = JdkHttpClient.builder().build();
        var calls = new AtomicInteger();
        var body = new AtomicReference<String>("unset");
        var httpClient = new HttpClient() {
            public SuccessfulHttpResponse execute(HttpRequest request) {
                calls.incrementAndGet();
                body.set(request.body());
                return delegate.execute(request);
            }

            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                delegate.execute(request, parser, listener);
            }
        };
        stubFor(get(urlEqualTo(PATH))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("api-key-secret")
                .langChain4jHttpClient(httpClient, HttpClientUnderTest.TEST_EXECUTOR).build());

        await(client.getConversation(ID));

        assertThat(calls).hasValue(1);
        assertThat(body.get()).isNull();
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    @Test
    void nonSuccessEventsOmitTheRequestId(WireMockRuntimeInfo server) throws InterruptedException {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var client = new HttpMemoryClient(URI.create(server.getHttpBaseUrl() + "/v1"), "api-key-secret",
                HttpTransports.select(JdkHttpClient.builder().build(), HttpClientUnderTest.TEST_EXECUTOR),
                new Jackson3JsonCodec(), new ClientLogging(sink));
        stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404).withHeader("X-Request-ID", "req-404")));

        assertThat(catchThrowable(() -> await(client.getConversation(ID)))).isInstanceOf(MemoryServiceException.class);

        var terminal = sink.awaitEvents(3).stream()
                .filter(entry -> entry.message().contains("event=operation.completed"))
                .findFirst().orElseThrow();
        assertThat(terminal.message()).contains("status=404", "outcome=failure").doesNotContain("requestId=");
    }
}
