package org.neo4j.agentmemory.conversation;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.http.JsonCodecUnderTest;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.neo4j.agentmemory.conversation.MessageRole.USER;
import static org.neo4j.agentmemory.testsupport.TestAwaitSupport.await;

@WireMockTest
@ParameterizedClass
@EnumSource(JsonCodecUnderTest.class)
class MessageMetadataTest {
    private static final UUID CONVERSATION_ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final UUID MESSAGE_ID = UUID.fromString("3b3af5b6-2bff-41fc-bdb2-c72e63822d47");
    private static final String PATH = "/v1/conversations/" + CONVERSATION_ID + "/messages";
    private final JsonCodecUnderTest codec;

    MessageMetadataTest(JsonCodecUnderTest codec) {
        this.codec = codec;
    }

    @AfterEach
    void validateContract() {
        OpenApiContract.assertOnlyDeclaredRequestProperties();
        OpenApiContract.assertEveryExchangeMatchesTheContract();
    }

    @Test
    void sendsSnapshotAndReturnsServiceMetadata(WireMockRuntimeInfo server) {
        stubFor(post(urlEqualTo(PATH)).willReturn(created().withHeader("Content-Type", "application/json")
                .withBody("""
                    {"id":"3b3af5b6-2bff-41fc-bdb2-c72e63822d47","role":"user","content":"hello",
                     "metadata":{"source":"test","priority":"high","stored":"yes"}}
                    """)));
        var input = new HashMap<>(Map.of("source", "test", "priority", "high"));
        var request = new NewMessage(USER, "hello", input);
        input.put("source", "changed");

        var message = await(client(server).addMessage(CONVERSATION_ID, request));

        verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"role":"user","content":"hello","metadata":{"source":"test","priority":"high"}}
                """)));
        assertThat(message.metadata()).containsExactlyInAnyOrderEntriesOf(
                Map.of("source", "test", "priority", "high", "stored", "yes"));
        assertThatThrownBy(() -> request.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private MemoryClient client(WireMockRuntimeInfo server) {
        return codec.client(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("key").build());
    }

    @Test
    void omitsEmptyMetadataInSingleAndBulkWrites(WireMockRuntimeInfo server) {
        var response = """
                {"id":"3b3af5b6-2bff-41fc-bdb2-c72e63822d47","role":"user","content":"hello"}
                """;
        stubFor(post(urlEqualTo(PATH)).willReturn(created().withHeader("Content-Type", "application/json")
                .withBody(response)));
        stubFor(post(urlEqualTo(PATH + "/bulk")).willReturn(created()
                .withHeader("Content-Type", "application/json").withBody("{\"messages\":[" + response + "]}")));
        var client = client(server);
        for (var request : List.of(NewMessage.user("hello"), new NewMessage(USER, "hello", null),
                new NewMessage(USER, "hello", Map.of()))) {
            assertThat(await(client.addMessage(CONVERSATION_ID, request)).metadata()).isEmpty();
            assertThat(await(client.addMessages(CONVERSATION_ID, List.of(request))))
                    .singleElement().satisfies(message -> assertThat(message.metadata()).isEmpty());
        }

        verify(3, postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"role":"user","content":"hello"}
                """)));
        verify(3, postRequestedFor(urlEqualTo(PATH + "/bulk")).withRequestBody(equalToJson("""
                {"messages":[{"role":"user","content":"hello"}]}
                """)));
    }

    @Test
    void preservesMetadataThroughBulkWritesAndHistoryReads(WireMockRuntimeInfo server) {
        var response = """
                {"messages":[
                  {"id":"3b3af5b6-2bff-41fc-bdb2-c72e63822d47","role":"user","content":"hello",
                   "metadata":{"source":"test","literal":"42","unicode":"世界"}},
                  {"id":"00000000-0000-0000-0000-000000000002","role":"assistant","content":"hi"}
                ]}
                """;
        stubFor(post(urlEqualTo(PATH + "/bulk")).willReturn(created()
                .withHeader("Content-Type", "application/json").withBody(response)));
        stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(response)));
        var client = client(server);
        var metadata = Map.of("source", "test", "literal", "42", "unicode", "世界");
        var added = await(client.addMessages(CONVERSATION_ID, List.of(
                new NewMessage(USER, "hello", metadata), NewMessage.assistant("hi"))));

        verify(postRequestedFor(urlEqualTo(PATH + "/bulk")).withRequestBody(equalToJson("""
                {"messages":[
                  {"role":"user","content":"hello","metadata":{"source":"test","literal":"42","unicode":"世界"}},
                  {"role":"assistant","content":"hi"}
                ]}
                """)));
        for (var messages : List.of(added, await(client.messages(CONVERSATION_ID)),
                await(client.messages(CONVERSATION_ID, 20)))) {
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).metadata()).isEqualTo(metadata);
            assertThat(messages.get(1).metadata()).isEmpty();
        }
    }

    @Test
    void messageMetadataIsASnapshotAndDoesNotChangeIdentity() {
        var input = new HashMap<>(Map.of("source", "test"));
        var message = new Message(MESSAGE_ID, USER, "hello", input);
        input.put("source", "changed");
        assertThat(message.metadata()).containsExactly(Map.entry("source", "test"));
        assertThat(message).isEqualTo(new Message(MESSAGE_ID, MessageRole.ASSISTANT, "other"));
        assertThat(message.hashCode()).isEqualTo(new Message(MESSAGE_ID, USER, "hello", null).hashCode());
        assertThat(new Message(MESSAGE_ID, USER, "hello", null).metadata()).isEmpty();
        assertThat(new Message(MESSAGE_ID, USER, "hello").metadata()).isEmpty();
        assertThat(NewMessage.assistant("hello").metadata()).isEmpty();
    }
}
