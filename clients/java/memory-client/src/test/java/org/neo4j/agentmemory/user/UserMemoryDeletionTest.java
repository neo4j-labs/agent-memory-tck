package org.neo4j.agentmemory.user;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.exception.ResponseDecodingException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class UserMemoryDeletionTest {
    @Test
    void deletedStatusIsComplete(WireMockRuntimeInfo server) {
        stubFor(delete(urlEqualTo("/v1/memory?userId=alice"))
                .willReturn(okJson("{\"userId\":\"alice\",\"status\":\"deleted\"}")));

        var result = await(client(server).deleteUserMemory("alice"));

        assertThat(result.userId()).isEqualTo("alice");
        assertThat(result.complete()).isTrue();
        verify(1, deleteRequestedFor(urlEqualTo("/v1/memory?userId=alice")));
    }

    @Test
    void incompleteStatusIsNotComplete(WireMockRuntimeInfo server) {
        stubFor(delete(urlEqualTo("/v1/memory?userId=alice"))
                .willReturn(okJson("{\"userId\":\"alice\",\"status\":\"incomplete\"}")));

        var result = await(client(server).deleteUserMemory("alice"));

        assertThat(result.userId()).isEqualTo("alice");
        assertThat(result.complete()).isFalse();
        verify(1, deleteRequestedFor(urlEqualTo("/v1/memory?userId=alice")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"userId\":\"alice\"}",
            "{\"userId\":\"alice\",\"status\":null}",
            "{\"userId\":\"alice\",\"status\":\"success\"}"})
    void missingOrUnknownStatusFailsDecoding(String body, WireMockRuntimeInfo server) {
        stubFor(delete(urlEqualTo("/v1/memory?userId=alice")).willReturn(okJson(body)));

        assertThatThrownBy(() -> await(client(server).deleteUserMemory("alice")))
                .isInstanceOf(ResponseDecodingException.class);
    }

    @Test
    void nullUserIdIsRejected(WireMockRuntimeInfo server) {
        assertThatThrownBy(() -> client(server).deleteUserMemory(null))
                .isInstanceOf(NullPointerException.class).hasMessage("userId");
        verify(0, deleteRequestedFor(urlPathEqualTo("/v1/memory")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t\n"})
    void blankUserIdIsRejected(String userId, WireMockRuntimeInfo server) {
        assertThatThrownBy(() -> client(server).deleteUserMemory(userId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("userId must not be blank");
        verify(0, deleteRequestedFor(urlPathEqualTo("/v1/memory")));
    }

    @Test
    void invalidUserIdIsAServiceFailure(WireMockRuntimeInfo server) {
        stubFor(delete(urlEqualTo("/v1/memory?userId=alice"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_user_id\"}")));

        assertThatThrownBy(() -> await(client(server).deleteUserMemory("alice")))
                .isInstanceOfSatisfying(MemoryServiceException.class,
                        failure -> assertThat(failure.statusCode()).isEqualTo(400));
        verify(1, deleteRequestedFor(urlEqualTo("/v1/memory?userId=alice")));
    }

    private static MemoryClient client(WireMockRuntimeInfo server) {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("key").build());
    }
}
