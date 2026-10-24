package org.neo4j.agentmemory.testsupport;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class OpenApiContractTest {
    private static final String PATH = "/v1/conversations";

    @AfterEach
    void restoreTheVendoredContract() {
        OpenApiContract.use(OpenApiContract.vendored());
    }

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

    @Test
    void skipsWhenThePublishedContractIsUnavailable(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo("/openapi.json")).willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> OpenApiContract.published(URI.create(server.getHttpBaseUrl() + "/openapi.json")))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("503");
    }

    @Test
    void skipsWhenThePublishedContractIsUnreachable(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo("/openapi.json")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> OpenApiContract.published(URI.create(server.getHttpBaseUrl() + "/openapi.json")))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void failsAnOperationTheContractInUseDoesNotDeclare() {
        OpenApiContract.use("""
                {"swagger": "2.0", "info": {"title": "NAMS Memory API", "version": "3.0.0"},
                 "paths": {"/v1/health": {"get": {"responses": {"200": {"description": "OK"}}}}},
                 "definitions": {}}
                """);

        assertThatThrownBy(() -> OpenApiContract.response("post", PATH))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("post " + PATH + " is not declared in the contract");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<!DOCTYPE html><html lang=\"en\"><head><meta charSet=\"utf-8\"/></head></html>",
            "{\"message\": \"Not Found\"}"})
    void failsADocumentThatIsNotAContract(String document) {
        assertThatThrownBy(() -> OpenApiContract.use(document))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is not a Swagger 2.0 contract");
    }

    @Test
    void followsARedirectToThePublishedContract(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo("/openapi.json")).willReturn(permanentRedirect("/v2/openapi.json")));
        stubFor(get(urlEqualTo("/v2/openapi.json")).willReturn(okJson(OpenApiContract.vendored())));

        assertThatNoException().isThrownBy(() -> OpenApiContract.use(
                OpenApiContract.published(URI.create(server.getHttpBaseUrl() + "/openapi.json"))));
    }
}
