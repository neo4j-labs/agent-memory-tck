package org.neo4j.agentmemory.conversation;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.ResponseDecodingException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
class ReadEnvelopeTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final String MESSAGES_PATH = "/v1/conversations/" + ID + "/messages";

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"conversations\":null}"})
    void listConversationsRejectsMissingOrNullArray(String body, WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo("/v1/conversations?limit=20")).willReturn(okJson(body)));

        assertDecodingFailure(client(server).listConversations(new ListConversations(20)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"messages\":null}"})
    void messagesRejectsMissingOrNullArray(String body, WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo(MESSAGES_PATH)).willReturn(okJson(body)));
        stubFor(get(urlEqualTo(MESSAGES_PATH + "?limit=20")).willReturn(okJson(body)));
        var conversation = conversation(server);

        assertDecodingFailure(conversation.messages());
        assertDecodingFailure(conversation.messages(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"conversations", "messages", "limitedMessages"})
    void explicitEmptyArraysReturnEmptyLists(String read, WireMockRuntimeInfo server) {
        var path = path(read);
        var body = read.equals("conversations")
                ? "{\"conversations\":[]}"
                : "{\"messages\":[]}";
        stubFor(get(urlEqualTo(path)).willReturn(okJson(body)));

        assertThat(await(read(read, server))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages", "limitedMessages"})
    void messageReadsPreserveServiceOrder(String read, WireMockRuntimeInfo server) {
        var path = path(read);
        stubFor(get(urlEqualTo(path)).willReturn(okJson("""
                {"messages":[
                  {"id":"00000000-0000-0000-0000-000000000003","role":"assistant","content":"latest"},
                  {"id":"00000000-0000-0000-0000-000000000001","role":"user","content":"earlier"}
                ]}
                """)));

        assertThat(await(read(read, server))).extracting(record -> ((Message) record).id())
                .containsExactly(
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void conversationListPreservesServiceOrder(WireMockRuntimeInfo server) {
        stubFor(get(urlEqualTo(path("conversations"))).willReturn(okJson("""
                {"conversations":[
                  {"id":"00000000-0000-0000-0000-000000000003"},
                  {"id":"00000000-0000-0000-0000-000000000001"}
                ]}
                """)));

        assertThat(await(client(server).listConversations(new ListConversations(20))))
                .extracting(Conversation::id)
                .containsExactly(
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private static CompletableFuture<? extends List<?>> read(String read, WireMockRuntimeInfo server) {
        if (read.equals("conversations")) {
            return client(server).listConversations(new ListConversations(20));
        }
        var conversation = conversation(server);
        return read.equals("limitedMessages") ? conversation.messages(20) : conversation.messages();
    }

    private static String path(String read) {
        return switch (read) {
            case "conversations" -> "/v1/conversations?limit=20";
            case "limitedMessages" -> MESSAGES_PATH + "?limit=20";
            default -> MESSAGES_PATH;
        };
    }

    private static void assertDecodingFailure(CompletableFuture<?> future) {
        assertThatThrownBy(() -> await(future))
                .isInstanceOf(ResponseDecodingException.class);
    }

    private static Conversation conversation(WireMockRuntimeInfo server) {
        return new Conversation(client(server), ID, null);
    }

    private static MemoryClient client(WireMockRuntimeInfo server) {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("key").build());
    }
}
