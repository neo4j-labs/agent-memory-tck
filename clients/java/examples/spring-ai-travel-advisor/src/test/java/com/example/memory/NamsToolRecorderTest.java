package com.example.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ReasoningStep;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.slf4j.LoggerFactory;

class NamsToolRecorderTest {
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration NORMAL = Duration.ofSeconds(10);
    private static List<NewToolCall> attempts() {
        return List.of(new NewToolCall("probe", "{\"n\":1}", "\"ok\"", ToolCallStatus.SUCCESS, Duration.ZERO),
                new NewToolCall("probe", "{\"n\":2}", "\"failed\"", ToolCallStatus.ERROR, Duration.ofMillis(1)));
    }
    private static List<MemoryServiceStub.RecordedRequest> requests(MemoryServiceStub stub, String path) {
        return stub.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals("/v1/reasoning/" + path)).toList();
    }
    @Test void recordsOneStepAndAllAttempts() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString();
            new NamsToolRecorder(stub.client(), NORMAL).record(id, attempts()).get(5, TimeUnit.SECONDS);
            var steps = requests(stub, "steps"); var tools = requests(stub, "tool-calls");
            assertEquals(1, steps.size()); assertEquals(2, tools.size());
            assertEquals("Tool execution round", steps.getFirst().body().path("reasoning").asText());
            assertEquals("Execute requested tools", steps.getFirst().body().path("actionTaken").asText());
            assertEquals(tools.get(0).body().path("stepId"), tools.get(1).body().path("stepId"));
            assertEquals(Set.of("{\"n\":1}", "{\"n\":2}"), tools.stream().map(r -> r.body().path("input").asText()).collect(Collectors.toSet()));
            assertEquals(Set.of("success", "error"), tools.stream().map(r -> r.body().path("status").asText()).collect(Collectors.toSet()));
            assertEquals(Set.of("\"ok\"", "\"failed\""), tools.stream().map(r -> r.body().path("output").asText()).collect(Collectors.toSet()));
            tools.forEach(r -> assertEquals("probe", r.body().path("toolName").asText()));
            assertEquals(Set.of(0L, 1L), tools.stream().map(r -> r.body().path("durationMs").asLong()).collect(Collectors.toSet()));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void returnsBeforeLookupOrStepCompletes(boolean lookup) throws Exception {
        try (var stub = new MemoryServiceStub(); var executor = Executors.newSingleThreadExecutor()) {
            String id = stub.seed(null, List.of()).id().toString();
            var held = stub.deferNext(lookup ? "GET" : "POST", lookup ? "/v1/conversations/" + id : "/v1/reasoning/steps");
            try {
                var recorder = new NamsToolRecorder(stub.client(), NORMAL);
                CompletableFuture<Void> completion = executor.submit(() -> recorder.record(id, attempts())).get(2, TimeUnit.SECONDS);
                held.awaitArrival(WAIT); assertFalse(completion.isDone()); held.release(); completion.get(5, TimeUnit.SECONDS);
            } finally { held.release(); }
        }
    }
    @Test void failedChildDoesNotSkipSibling() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString();
            stub.failNext("POST", "/v1/reasoning/tool-calls", 503, "{}");
            var completion = new NamsToolRecorder(stub.client(), NORMAL).record(id, attempts());
            assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
            assertEquals(2, requests(stub, "tool-calls").size());
        }
    }
    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void failedOrTimedOutParentStartsNoChildren(boolean lookup, boolean timeout) throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString();
            String method = lookup ? "GET" : "POST", path = lookup ? "/v1/conversations/" + id : "/v1/reasoning/steps";
            var held = timeout ? stub.deferNext(method, path) : null;
            if (!timeout) stub.failNext(method, path, 503, "{}");
            try {
                var completion = new NamsToolRecorder(stub.client(), timeout ? Duration.ofMillis(250) : NORMAL).record(id, attempts());
                if (held != null) held.awaitArrival(WAIT);
                assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
                if (held != null) { held.release(); held.awaitCompletion(WAIT); }
                assertEquals(0, requests(stub, "tool-calls").size());
            } finally { if (held != null) held.release(); }
        }
    }
    @Test void emptyBatchAndInvalidIdDoNoRemoteWork() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString(); int before = stub.requests().size();
            var recorder = new NamsToolRecorder(stub.client(), NORMAL);
            recorder.record(id, List.of()).get(2, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> recorder.record("invalid", attempts()).get(2, TimeUnit.SECONDS));
            assertEquals(before, stub.requests().size());
        }
    }
    @Test void capturesImmutableBatch() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString(); var held = stub.deferNext("GET", "/v1/conversations/" + id);
            try {
                var submitted = new ArrayList<>(attempts());
                var completion = new NamsToolRecorder(stub.client(), NORMAL).record(id, submitted);
                held.awaitArrival(WAIT); submitted.clear(); held.release(); completion.get(5, TimeUnit.SECONDS);
                assertEquals(2, requests(stub, "tool-calls").size());
            } finally { held.release(); }
        }
    }
    @Test void childTimeoutCompletesWithoutRetry() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString(); var held = stub.deferNext("POST", "/v1/reasoning/tool-calls");
            try {
                var completion = new NamsToolRecorder(stub.client(), Duration.ofMillis(250)).record(id, attempts());
                held.awaitArrival(WAIT); assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
                held.release(); held.awaitCompletion(WAIT); assertEquals(2, requests(stub, "tool-calls").size());
            } finally { held.release(); }
        }
    }
    @Test void synchronousChildDispatchFailurePreservesSibling() throws Exception {
        var step = mock(ReasoningStep.class); var batch = attempts();
        when(step.recordToolCall(batch.get(0))).thenThrow(new IllegalStateException("dispatch"));
        when(step.recordToolCall(batch.get(1))).thenReturn(CompletableFuture.completedFuture(null));
        var recorder = mockedRecorder(step);
        assertThrows(ExecutionException.class, () -> recorder.record(UUID.randomUUID().toString(), batch).get(5, TimeUnit.SECONDS));
        verify(step, times(1)).recordToolCall(batch.get(1));
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void synchronousParentDispatchFailureStartsNoChildren(boolean lookup) throws Exception {
        var client = mock(MemoryClient.class); var conversation = mock(Conversation.class);
        var failure = new IllegalStateException("dispatch");
        if (lookup) when(client.getConversation(any(UUID.class))).thenThrow(failure);
        else {
            when(client.getConversation(any(UUID.class))).thenReturn(CompletableFuture.completedFuture(conversation));
            when(conversation.recordStep(any(NewReasoningStep.class))).thenThrow(failure);
        }
        var recorder = new NamsToolRecorder(client, NORMAL);
        assertThrows(ExecutionException.class, () -> recorder.record(UUID.randomUUID().toString(), attempts()).get(5, TimeUnit.SECONDS));
        verify(conversation, times(lookup ? 0 : 1)).recordStep(any(NewReasoningStep.class));
    }
    @Test void nullBatchAndNullAttemptFailThroughCompletion() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed(null, List.of()).id().toString(); int before = stub.requests().size();
            var recorder = new NamsToolRecorder(stub.client(), NORMAL);
            assertThrows(ExecutionException.class, () -> recorder.record(id, null).get(2, TimeUnit.SECONDS));
            var invalid = new ArrayList<NewToolCall>(); invalid.add(null);
            assertThrows(ExecutionException.class, () -> recorder.record(id, invalid).get(2, TimeUnit.SECONDS));
            assertEquals(before, stub.requests().size());
        }
    }
    private NamsToolRecorder mockedRecorder(ReasoningStep step) {
        var client = mock(MemoryClient.class); var conversation = mock(Conversation.class);
        when(client.getConversation(any(UUID.class))).thenReturn(CompletableFuture.completedFuture(conversation));
        when(conversation.recordStep(any(NewReasoningStep.class))).thenReturn(CompletableFuture.completedFuture(step));
        return new NamsToolRecorder(client, NORMAL);
    }
    @Test void recordingErrorsDoNotLogContent() throws Exception {
        var step = mock(ReasoningStep.class);
        var attempt = new NewToolCall("probe", "sensitive-input", "sensitive-output", ToolCallStatus.ERROR, Duration.ZERO);
        when(step.recordToolCall(attempt)).thenThrow(new IllegalStateException("sensitive-exception-message"));
        Logger logger = (Logger) LoggerFactory.getLogger(NamsToolRecorder.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var recorder = mockedRecorder(step);
            assertThrows(ExecutionException.class, () -> recorder.record(UUID.randomUUID().toString(), List.of(attempt)).get(5, TimeUnit.SECONDS));
            assertFalse(appender.list.isEmpty());
            appender.list.forEach(event -> assertNull(event.getThrowableProxy()));
            String capturedLogs = appender.list.stream().map(e -> e.getFormattedMessage() + String.valueOf(e.getThrowableProxy())).collect(Collectors.joining());
            assertFalse(capturedLogs.contains("sensitive-input")); assertFalse(capturedLogs.contains("sensitive-output"));
            assertFalse(capturedLogs.contains("sensitive-exception-message"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
