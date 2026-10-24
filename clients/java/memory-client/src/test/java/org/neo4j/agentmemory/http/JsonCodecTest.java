package org.neo4j.agentmemory.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.neo4j.agentmemory.conversation.ConversationContext;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.entity.Entity;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.conversation.MessageRole.USER;

@ParameterizedClass
@EnumSource(JsonCodecUnderTest.class)
class JsonCodecTest {
    private static final UUID CONVERSATION_ID =
            UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final UUID MESSAGE_ID =
            UUID.fromString("3b3af5b6-2bff-41fc-bdb2-c72e63822d47");

    private final JsonCodecUnderTest codecUnderTest;
    private final JsonCodec codec;

    JsonCodecTest(JsonCodecUnderTest codecUnderTest) {
        this.codecUnderTest = codecUnderTest;
        this.codec = codecUnderTest.codec();
    }

    @Test
    void encodesHostedMessageRequestAndLowercaseRole() {
        var messageJson = new String(codec.encode(new NewMessage(USER, "hello", Map.of("source", "test"))), UTF_8);

        assertThat(messageJson).isEqualTo("{\"role\":\"user\",\"content\":\"hello\",\"metadata\":{\"source\":\"test\"}}");
    }

    @Test
    void omitsNullPropertiesButPreservesEmptyAndZeroValues() {
        var json = codec.encode(new OptionalRequestFixture(null, "", 0L));

        assertThat(codec.decode(json, Map.class))
                .containsEntry("output", "")
                .containsEntry("durationMs", 0)
                .doesNotContainKey("result");
    }

    private record OptionalRequestFixture(String result, String output, Long durationMs) {}

    @Test
    void decodesRichHostedResponseValuesAndIgnoresUnknownFields() {
        var json = """
                {
                  "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                  "userId": "alice",
                  "workspaceId": "ignored-by-this-slice"
                }
                """;

        var conversation = codec.decode(json.getBytes(UTF_8), ConversationFixture.class);

        assertThat(conversation.id()).isEqualTo(CONVERSATION_ID);
        assertThat(conversation.userId()).isEqualTo("alice");
    }

    @Test
    void decodesHostedContextSnippetThroughFacade() {
        var json = """
                {
                  "reflections": [{
                    "id": "13b46f84-e2b5-4bb5-bce3-77783824196d",
                    "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                    "content": "user values clarity"
                  }],
                  "observations": [{
                    "id": "d37335af-aa1f-4b56-be19-b99c7b7f089e",
                    "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                    "content": "long-form messages"
                  }],
                  "recentMessages": [{
                    "id": "3b3af5b6-2bff-41fc-bdb2-c72e63822d47",
                    "role": "user",
                    "content": "hello"
                  }]
                }
                """;

        var context = codec.decode(json.getBytes(UTF_8), ConversationContext.class);

        assertThat(context.reflections()).singleElement()
                .satisfies(reflection -> assertThat(reflection.content()).isEqualTo("user values clarity"));
        assertThat(context.observations()).singleElement()
                .satisfies(observation -> assertThat(observation.content()).isEqualTo("long-form messages"));
        assertThat(context.recentMessages()).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo(MESSAGE_ID);
            assertThat(message.role()).isEqualTo(USER);
            assertThat(message.content()).isEqualTo("hello");
        });
    }

    @Test
    void rejectsMismatchedScalarTypes() {
        var json = """
                {
                  "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                  "userId": 42
                }
                """;
        assertThatThrownBy(() -> codec.decode(json.getBytes(UTF_8), ConversationFixture.class))
                .isInstanceOf(JsonCodecException.class).hasMessage("Could not decode JSON");
    }

    @Test
    void rejectsNumericEnums() {
        var json = """
                {
                  "id": "3b3af5b6-2bff-41fc-bdb2-c72e63822d47",
                  "role": 0,
                  "content": "hello"
                }
                """;
        assertThatThrownBy(() -> codec.decode(json.getBytes(UTF_8), Message.class))
                .isInstanceOf(JsonCodecException.class).hasMessage("Could not decode JSON");
    }

    @Test
    void encodesAndDecodesHostedToolCallStatuses() {
        var encoded = new String(
                codec.encode(new ToolStatusFixture(ToolCallStatus.SUCCESS)), UTF_8);

        assertThat(encoded).isEqualTo("{\"status\":\"success\"}");

        var decoded = codec.decode(
                "{\"status\":\"timeout\"}".getBytes(UTF_8),
                ToolStatusFixture.class);

        assertThat(decoded.status()).isEqualTo(ToolCallStatus.TIMEOUT);
    }

    @Test
    void namesItsMajor() {
        assertThat(codec.name()).isEqualTo(codecUnderTest == JsonCodecUnderTest.JACKSON_3 ? "jackson3" : "jackson2");
    }

    @Test
    void roundTripsNonAsciiContent() {
        var json = codec.encode(new NewMessage(USER, "zażółć gęślą jaźń 🎉"));
        assertThat(codec.decode(json, NewMessage.class).content()).isEqualTo("zażółć gęślą jaźń 🎉");
    }

    @Test
    void encodesRequestMapsInInsertionOrder() {
        var body = new LinkedHashMap<String, Object>();
        body.put("stepId", MESSAGE_ID);
        body.put("toolName", "search");
        body.put("status", ToolCallStatus.TIMEOUT);
        body.put("durationMs", 12L);
        assertThat(new String(codec.encode(body), UTF_8)).isEqualTo(
                "{\"stepId\":\"" + MESSAGE_ID + "\",\"toolName\":\"search\",\"status\":\"timeout\",\"durationMs\":12}");
    }

    @Test
    void decodesEntityWithAndWithoutDescription() {
        var described = codec.decode("{\"description\":\"d\"}".getBytes(UTF_8), Entity.class);
        assertThat(described.description()).isEqualTo("d");
        var absent = codec.decode("{}".getBytes(UTF_8), Entity.class);
        assertThat(absent.description()).isNull();
        assertThat(absent.getDescription()).isEmpty();
    }

    @Test
    void decodesStringMetadataAndBoxedCounts() {
        var json = "{\"id\":\"" + CONVERSATION_ID + "\",\"metadata\":{\"k\":\"v\"},\"messageCount\":5}";
        var decoded = codec.decode(json.getBytes(UTF_8), MetadataFixture.class);
        assertThat(decoded.id()).isEqualTo(CONVERSATION_ID);
        assertThat(decoded.metadata()).containsExactly(Map.entry("k", "v"));
        assertThat(decoded.messageCount()).isEqualTo(5L);
        assertThat(codec.decode("{}".getBytes(UTF_8), MetadataFixture.class).messageCount()).isNull();
    }

    @Test
    void readsEnumsCaseInsensitively() {
        for (var status : new String[] {"TIMEOUT", "Timeout"}) {
            var json = "{\"status\":\"" + status + "\"}";
            assertThat(codec.decode(json.getBytes(UTF_8), ToolStatusFixture.class).status())
                    .isEqualTo(ToolCallStatus.TIMEOUT);
        }
    }

    @Test
    void rejectsUnknownEnumValues() {
        assertDecodeFailure("{\"status\":\"weird\"}", ToolStatusFixture.class);
    }

    @Test
    void rejectsNonStringScalarsForStrings() {
        for (var value : new String[] {"42", "true", "{}"}) {
            assertDecodeFailure("{\"userId\":" + value + "}", ConversationFixture.class);
        }
    }

    @Test
    void rejectsTextualNumbers() {
        assertDecodeFailure("{\"messageCount\":\"5\"}", MetadataFixture.class);
    }

    @Test
    void rejectsNonStringMetadataValues() {
        assertDecodeFailure("{\"metadata\":{\"k\":1}}", MetadataFixture.class);
    }

    @Test
    void rejectsTrailingTokens() {
        assertDecodeFailure("{\"id\":\"" + CONVERSATION_ID + "\"} {}", ConversationFixture.class);
    }

    @Test
    void rejectsNullForPrimitives() {
        assertDecodeFailure("{\"count\":null}", PrimitiveFixture.class);
    }

    @Test
    void readsNullListsAsEmptyWhereTheRecordNormalizes() {
        var context = codec.decode("{\"reflections\":null}".getBytes(UTF_8), ConversationContext.class);
        assertThat(context.reflections()).isEmpty();
        assertThat(context.observations()).isEmpty();
        assertThat(context.recentMessages()).isEmpty();
    }

    @Test
    void ordersNonCreatorPropertiesAlphabetically() {
        assertThat(new String(codec.encode(new GetterFixture()), UTF_8)).isEqualTo("{\"alpha\":1,\"zeta\":2}");
    }

    @Test
    void limitsNestingDepth() {
        var accepted = "[".repeat(500) + "0" + "]".repeat(500);
        assertThat(codec.decode(accepted.getBytes(UTF_8), Object.class)).isNotNull();
        assertDecodeFailure("[".repeat(501) + "0" + "]".repeat(501), Object.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertDecodeFailure("{", Object.class);
    }

    @Test
    void wrapsEncodingFailures() {
        assertThatThrownBy(() -> codec.encode(new FailingGetterFixture()))
                .isInstanceOf(JsonCodecException.class).hasMessage("Could not encode JSON")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("cannot encode");
    }

    private void assertDecodeFailure(String json, Class<?> type) {
        assertThatThrownBy(() -> codec.decode(json.getBytes(UTF_8), type))
                .isInstanceOf(JsonCodecException.class).hasMessage("Could not decode JSON");
    }

    private record MetadataFixture(UUID id, Map<String, String> metadata, Long messageCount) {}

    private record PrimitiveFixture(int count) {}

    static class GetterFixture {
        public int getZeta() { return 2; }
        public int getAlpha() { return 1; }
    }

    static class FailingGetterFixture {
        public String getValue() { throw new IllegalStateException("cannot encode"); }
    }

    private record ConversationFixture(UUID id, String userId) {}

    private record ToolStatusFixture(ToolCallStatus status) {}
}
