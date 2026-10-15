package org.neo4j.agentmemory.conversation;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class ConversationDetailsTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");

    @AfterEach
    void validateContract() {
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    @Test
    void decodesDistinctListAndDetailShapes(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo("/v1/conversations?limit=50")).willReturn(okJson("""
            {"conversations":[{
              "id":"a2f55d70-838f-4c41-ae7d-dad30fd25720","userId":"alice",
              "title":"Find hotels in Zermatt","firstMessageSnippet":"Find hotels",
              "messageCount":2,"metadata":{"title":"Find hotels in Zermatt","origin":"java"},
              "createdAt":"2026-09-24T10:00:00+02:00","updatedAt":"2026-09-24T10:30:00+02:00"
            }]}
            """)));
        stubFor(get(urlEqualTo("/v1/conversations/" + ID)).willReturn(okJson("""
            {"id":"a2f55d70-838f-4c41-ae7d-dad30fd25720","userId":"alice",
             "metadata":{"title":"Find hotels in Zermatt","origin":"java"},
             "createdAt":"2026-09-24T08:00:00Z","updatedAt":"2026-09-24T08:30:00Z"}
            """)));
        var client = client(server);
        var listed = await(client.listConversations(new ListConversations(50))).get(0);
        var detail = await(client.getConversation(ID));

        assertThat(listed.id()).isEqualTo(ID);
        assertThat(detail.id()).isEqualTo(ID);
        assertThat(listed.userId()).contains("alice");
        assertThat(detail.title()).contains("Find hotels in Zermatt");
        assertThat(listed.title()).isEqualTo(detail.title());
        assertThat(listed.metadata()).containsExactlyInAnyOrderEntriesOf(
                Map.of("title", "Find hotels in Zermatt", "origin", "java"));
        assertThat(detail.metadata()).isEqualTo(listed.metadata());
        assertThat(listed.createdAt()).contains(Instant.parse("2026-09-24T08:00:00Z"));
        assertThat(listed.updatedAt()).contains(Instant.parse("2026-09-24T08:30:00Z"));
        assertThat(detail.createdAt()).isEqualTo(listed.createdAt());
        assertThat(detail.updatedAt()).isEqualTo(listed.updatedAt());
        assertThat(listed.firstMessageSnippet()).contains("Find hotels");
        assertThat(listed.messageCount()).contains(2L);
        assertThat(detail.firstMessageSnippet()).isEmpty();
        assertThat(detail.messageCount()).isEmpty();
        assertThatThrownBy(() -> detail.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesMissingFieldsAndExplicitZeroAndEmptyValues(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo("/v1/conversations/" + ID))
                .willReturn(okJson("{\"id\":\"" + ID + "\"}")));
        stubFor(get(urlEqualTo("/v1/conversations?limit=1")).willReturn(okJson("""
            {"conversations":[{"id":"a2f55d70-838f-4c41-ae7d-dad30fd25720",
              "title":"","firstMessageSnippet":"","messageCount":0,"metadata":{}}]}
            """)));
        var client = client(server);
        var absent = await(client.getConversation(ID));
        assertThat(absent.metadata()).isEmpty();
        assertThat(absent.title()).isEmpty();
        assertThat(absent.createdAt()).isEmpty();
        assertThat(absent.updatedAt()).isEmpty();
        assertThat(absent.firstMessageSnippet()).isEmpty();
        assertThat(absent.messageCount()).isEmpty();
        var empty = await(client.listConversations(new ListConversations(1))).get(0);
        assertThat(empty.metadata()).isEmpty();
        assertThat(empty.title()).contains("");
        assertThat(empty.firstMessageSnippet()).contains("");
        assertThat(empty.messageCount()).contains(0L);
    }

    @Test
    void copiesMetadataAndPreservesTopLevelTitleAndUuidIdentity(WireMockRuntimeInfo server) {
        var metadata = new HashMap<>(Map.of("title", "metadata title"));
        var client = client(server);
        var value = new Conversation(client, ID, null, metadata, null, null, "", null, null);
        metadata.put("title", "changed");
        assertThat(value.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of("title", "metadata title"));
        assertThat(value.title()).contains("");
        var oldShape = new Conversation(client, ID, "alice");
        assertThat(oldShape.metadata()).isEmpty();
        assertThat(value).isEqualTo(oldShape);
        assertThat(value.hashCode()).isEqualTo(oldShape.hashCode());
    }

    private static MemoryClient client(WireMockRuntimeInfo server) {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("key").build());
    }
}
