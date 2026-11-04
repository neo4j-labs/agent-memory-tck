package com.example.memory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NamsChatMemoryTest {
    private final NamsToolRecorder recorder = mock(NamsToolRecorder.class);
    private NamsChatMemory memory(MemoryServiceStub stub) {
        return new NamsChatMemory(stub.client(), Duration.ofSeconds(2), new NamsContextFormatter(), recorder);
    }
    private List<NewToolCall> attempts() {
        return List.of(new NewToolCall("A", "{}", "\"a\"", ToolCallStatus.SUCCESS, Duration.ofMillis(1)), new NewToolCall("B", "{}", "\"b\"", ToolCallStatus.SUCCESS, Duration.ofMillis(2)));
    }
    @Test void appendsOnlyNewTextAndPreservesRepeatedOccurrences() {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of());
            memory(stub).add(c.id().toString(), List.of(new UserMessage("same"), new UserMessage("same"), new AssistantMessage("answer")));
            assertEquals(List.of("same", "same", "answer"), stub.messages(c.id()).stream().map(org.neo4j.agentmemory.conversation.Message::content).toList());
            var writes = stub.requests().stream().filter(r -> r.path().endsWith("/messages/bulk")).toList();
            assertEquals(1, writes.size());
            assertEquals(List.of("same", "same", "answer"), writes.getFirst().body().path("messages").findValuesAsText("content"));
            assertFalse(stub.requests().stream().anyMatch(r -> r.method().equals("POST") && r.path().endsWith("/messages")));
            assertFalse(stub.requests().stream().anyMatch(r -> r.path().endsWith("/context")));
        }
    }
    @Test void generatedSystemAndToolOnlyAssistantAreNotTextHistory() {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of()); int before = stub.requests().size();
            memory(stub).add(c.id().toString(), List.of(new SystemMessage("recalled"), AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "A", "{}"))).build()));
            assertEquals(before, stub.requests().size());
            memory(stub).add(c.id().toString(), AssistantMessage.builder().content("visible").toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "A", "{}"))).build());
            assertEquals("visible", stub.messages(c.id()).getFirst().content());
        }
    }
    @Test void clearNeverDeletesHistory() {
        try (var stub = new MemoryServiceStub()) {
            int before = stub.requests().size();
            assertThrows(UnsupportedOperationException.class, () -> memory(stub).clear("anything"));
            assertEquals(before, stub.requests().size());
        }
    }
    @Test void completedToolMessageDispatchesNativeEvidenceOnce() {
        try (var stub = new MemoryServiceStub()) {
            var attempts = attempts();
            memory(stub).add("id", ToolResponseMessage.builder().metadata(Map.of(NamsChatMemory.TOOL_CALLS_METADATA_KEY, attempts)).build());
            verify(recorder, times(1)).record("id", attempts);
            assertTrue(stub.requests().isEmpty());
        }
    }
    @Test void partialRoundUsesSameRecorder() {
        try (var stub = new MemoryServiceStub()) {
            var attempts = attempts(); memory(stub).recordToolRound("id", attempts);
            verify(recorder, times(1)).record("id", attempts);
        }
    }
    @Test void missingToolEvidenceDoesNotInventExecutionData() {
        try (var stub = new MemoryServiceStub()) {
            assertDoesNotThrow(() -> memory(stub).add("id", ToolResponseMessage.builder().build()));
            verifyNoInteractions(recorder); assertTrue(stub.requests().isEmpty());
        }
    }
    @Test void invalidToolMetadataIsLoggedWithoutContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(NamsChatMemory.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try (var stub = new MemoryServiceStub()) {
            assertDoesNotThrow(() -> memory(stub).add("id", ToolResponseMessage.builder().metadata(Map.of(NamsChatMemory.TOOL_CALLS_METADATA_KEY, "sensitive-tool-output")).build()));
            verifyNoInteractions(recorder); assertFalse(logs.list.isEmpty());
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("sensitive-tool-output")));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test void freshGetObservesRemoteChanges() {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of()); var memory = memory(stub);
            assertEquals(1, memory.get(c.id().toString()).size());
            org.neo4j.agentmemory.MemoryClient.await(c.addMessage(NewMessage.user("external update")), Duration.ofSeconds(2));
            assertTrue(memory.get(c.id().toString()).stream().anyMatch(m -> "external update".equals(m.getText())));
        }
    }
    @Test void deletedIdLookupIs404WithoutCreation() {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of()); stub.delete(c.id()); int before = stub.requests().size();
            assertThrows(MemoryClientException.class, () -> memory(stub).get(c.id().toString()));
            assertEquals(1, stub.requests().size() - before);
            assertEquals("GET", stub.requests().getLast().method());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"context", "user", "assistant"})
    void requiredFailureStopsWithoutRetry(String operation) {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of()); String path = "/v1/conversations/" + c.id() + (operation.equals("context") ? "/context" : "/messages/bulk");
            stub.failNext(operation.equals("context") ? "GET" : "POST", path, 503, "{\"error\":\"failure\"}"); int before = stub.requests().size();
            assertThrows(MemoryClientException.class, () -> { if (operation.equals("context")) memory(stub).get(c.id().toString()); else memory(stub).add(c.id().toString(), operation.equals("user") ? new UserMessage("new text") : new AssistantMessage("new text")); });
            assertEquals(1, stub.requests().subList(before, stub.requests().size()).stream().filter(r -> r.path().equals(path)).count());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"{\"role\":\"user\",\"content\":\"new text\"}", "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"assistant\",\"content\":\"new text\"}", "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"user\",\"content\":\"wrong\"}"})
    void insufficientAcknowledgementFails(String response) {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of()); stub.failNextMessage(c.id(), "user", 200, "{\"messages\":[" + response + "]}");
            assertThrows(MemoryClientException.class, () -> memory(stub).add(c.id().toString(), new UserMessage("new text")));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "null", "empty", "short", "extra", "second-id", "second-role", "second-content", "reordered"})
    void insufficientBatchAcknowledgementFailsWithoutRetry(String fault) {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of());
            String first = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"user\",\"content\":\"question\"}";
            String second = "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"role\":\"assistant\",\"content\":\"answer\"}";
            String response = switch (fault) {
                case "missing" -> "{}";
                case "null" -> "{\"messages\":null}";
                case "empty" -> "{\"messages\":[]}";
                case "short" -> "{\"messages\":[" + first + "]}";
                case "extra" -> "{\"messages\":[" + first + "," + second + "," + second + "]}";
                case "second-id" -> "{\"messages\":[" + first + ",{\"role\":\"assistant\",\"content\":\"answer\"}]}";
                case "second-role" -> "{\"messages\":[" + first + "," + second.replace("assistant", "user") + "]}";
                case "second-content" -> "{\"messages\":[" + first + "," + second.replace("answer", "wrong") + "]}";
                case "reordered" -> "{\"messages\":[" + second + "," + first + "]}";
                default -> throw new AssertionError(fault);
            };
            String path = "/v1/conversations/" + c.id() + "/messages/bulk";
            stub.failNext("POST", path, 200, response);
            var failure = assertThrows(MemoryClientException.class,
                () -> memory(stub).add(c.id().toString(), List.of(new UserMessage("question"), new AssistantMessage("answer"))));
            assertEquals("Insufficient memory message acknowledgement", failure.getMessage());
            assertEquals(1, stub.requests().stream().filter(r -> r.path().equals(path)).count());
        }
    }
    @Test void validatesWholeTextBatchBeforeWrites() {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of()); int before = stub.requests().size();
            var media = UserMessage.builder().text("image").media(new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.test/image.png"))).build();
            assertThrows(IllegalArgumentException.class, () -> memory(stub).add(c.id().toString(), List.of(new UserMessage("valid"), media)));
            assertThrows(IllegalArgumentException.class, () -> memory(stub).add(c.id().toString(), List.of(new UserMessage("valid"), new UserMessage(" "))));
            assertEquals(before, stub.requests().size());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"lookup", "context", "append"})
    void requiredNativeStagesAreBounded(String operation) {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of());
            String path = "/v1/conversations/" + c.id() + (operation.equals("context") ? "/context" : operation.equals("append") ? "/messages/bulk" : "");
            int before = stub.requests().size();
            var deferred = stub.deferNext(operation.equals("append") ? "POST" : "GET", path);
            var memory = new NamsChatMemory(stub.client(), Duration.ofMillis(100), new NamsContextFormatter(), recorder);
            try {
                assertThrows(MemoryClientException.class, () -> { if (operation.equals("append")) memory.add(c.id().toString(), new UserMessage("new")); else memory.get(c.id().toString()); });
                deferred.awaitArrival(Duration.ofSeconds(2));
                assertEquals(1, stub.requests().subList(before, stub.requests().size()).stream().filter(r -> r.path().equals(path) && r.method().equals(operation.equals("append") ? "POST" : "GET")).count());
            } finally { deferred.release(); deferred.awaitCompletion(Duration.ofSeconds(2)); }
        }
    }
    @Test void remoteBatchFailureDoesNotRollBackEarlierWrites() {
        try (var stub = new MemoryServiceStub()) {
            var c = stub.seed("test", List.of());
            memory(stub).add(c.id().toString(), new UserMessage("durable"));
            stub.failNextMessage(c.id(), "assistant", 503, "{}");
            assertThrows(MemoryClientException.class, () -> memory(stub).add(c.id().toString(), List.of(new UserMessage("next"), new AssistantMessage("failed"))));
            assertEquals(List.of("durable"), stub.messages(c.id()).stream().map(org.neo4j.agentmemory.conversation.Message::content).toList());
        }
    }
}
