package org.neo4j.agentmemory.conversation;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class CreateConversationMetadataTest {
    private static final String PATH = "/v1/conversations";
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");

    @AfterEach
    void validateContract() {
        OpenApiContract.assertOnlyDeclaredRequestProperties();
        OpenApiContract.assertEveryExchangeMatchesTheContract();
    }

    @Test
    void sendsExactTitleAndReturnsAssignedId(WireMockRuntimeInfo server) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                    {"id":"a2f55d70-838f-4c41-ae7d-dad30fd25720",
                     "metadata":{"title":"Find hotels in Zermatt","origin":"java"}}
                    """)));
        var input = new HashMap<>(Map.of("title", "Find hotels in Zermatt", "origin", "java"));
        var request = new CreateConversation(" alice ", input);
        input.put("title", "changed after construction");
        var created = await(client(server).createConversation(request));
        assertThat(created.id()).isEqualTo(ID);
        assertThat(created.title()).contains("Find hotels in Zermatt");
        assertThat(created.createdAt()).isEmpty();
        assertThat(created.updatedAt()).isEmpty();
        assertThatThrownBy(() -> request.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
            {"userId":"alice","metadata":{"title":"Find hotels in Zermatt","origin":"java"}}
            """)));
    }

    @Test
    void preservesLegacyOmissionAndNormalizesEmptyMetadata(WireMockRuntimeInfo server) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + ID + "\"}")));
        var client = client(server);
        await(client.createConversation(new CreateConversation()));
        await(client.createConversation(new CreateConversation("alice")));
        await(client.createConversation(new CreateConversation(null, null)));
        await(client.createConversation(new CreateConversation(null, Map.of())));
        await(client.createConversation(new CreateConversation(null, Map.of("title", "Find hotels in Zermatt"))));
        verify(3, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("{}")));
        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("{\"userId\":\"alice\"}")));
        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson(
                "{\"metadata\":{\"title\":\"Find hotels in Zermatt\"}}")));
        assertThat(new CreateConversation().metadata()).isEmpty();
        assertThat(new CreateConversation().getMetadata()).isEmpty();
        assertThat(new CreateConversation("alice").metadata()).isEmpty();
        assertThat(new CreateConversation(null, null).metadata()).isEmpty();
        assertThat(new CreateConversation(null, Map.of()).getMetadata()).isEmpty();
    }

    private static MemoryClient client(WireMockRuntimeInfo server) {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("key").build());
    }
}
