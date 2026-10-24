package org.neo4j.agentmemory.conversation;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class ConversationMessageLimitTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final String PATH = "/v1/conversations/" + ID + "/messages";

    @AfterEach
    void validateContract() {
        OpenApiContract.assertEveryExchangeMatchesTheContract();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 200})
    void sendsExactLimit(int limit, WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo(PATH + "?limit=" + limit)).willReturn(okJson("{\"messages\":[]}")));
        assertThat(await(conversation(server).messages(limit))).isEmpty();
        verify(1, getRequestedFor(urlEqualTo(PATH + "?limit=" + limit)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 201})
    void rejectsInvalidLimitsBeforeHttp(int limit, WireMockRuntimeInfo server) {
        assertThatThrownBy(() -> conversation(server).messages(limit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 200");
        verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void noArgumentReadOmitsLimit(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo(PATH)).willReturn(okJson("{\"messages\":[]}")));
        assertThat(await(conversation(server).messages())).isEmpty();
        verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void preservesServerOrderIdentityRolesAndRepeatedContent(WireMockRuntimeInfo server) {
        var newest = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var middle = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var oldest = UUID.fromString("00000000-0000-0000-0000-000000000002");
        stubFor(get(urlEqualTo(PATH + "?limit=20")).willReturn(okJson("""
            {"messages":[
              {"id":"00000000-0000-0000-0000-000000000003","role":"assistant","content":"same"},
              {"id":"00000000-0000-0000-0000-000000000001","role":"user","content":"next"},
              {"id":"00000000-0000-0000-0000-000000000002","role":"assistant","content":"same"}
            ]}
            """)));
        var messages = await(conversation(server).messages(20));
        assertThat(messages).extracting(Message::id).containsExactly(newest, middle, oldest);
        assertThat(messages).extracting(Message::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(messages).extracting(Message::content).containsExactly("same", "next", "same");
        assertThatThrownBy(messages::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Conversation conversation(WireMockRuntimeInfo server) {
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("key").build());
        return new Conversation(client, ID, null);
    }
}
