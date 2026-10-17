package org.neo4j.agentmemory.testsupport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class OpenApiContractTest {
    private static final String PATH = "/v1/conversations";

    @Test
    void rejectsUndeclaredPropertyInCreateConversationRequest(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo(PATH)).willReturn(OpenApiContract.response("post", PATH)));
        // "metdata" deliberately misspells the declared "metadata" property.
        var request = HttpRequest.newBuilder(URI.create(server.getHttpBaseUrl() + PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"metdata\":{\"title\":\"Find hotels in Zermatt\"}}"))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());

        assertThatThrownBy(OpenApiContract::assertOnlyDeclaredRequestProperties)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("POST " + PATH + " sends undeclared property 'metdata'");
    }
}
