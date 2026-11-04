package com.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doThrow;

class NamsToolCallingManagerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private ToolCallback callback(boolean direct, BiFunction<String, ToolContext, String> action) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("probe").description("probe").inputSchema("{}").build(); }
            public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(direct).build(); }
            public String call(String input) { return action.apply(input, null); }
            public String call(String input, ToolContext context) { return action.apply(input, context); }
        };
    }
    private ChatModel model(java.util.function.Function<Prompt, ChatResponse> script) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) { return script.apply(prompt); }
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return OpenAiChatOptions.builder().build(); }
        };
    }
    private ChatResponse response(String text, String... inputs) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < inputs.length; i++) calls.add(new AssistantMessage.ToolCall("call-" + i, "function", "probe", inputs[i]));
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).toolCalls(calls).build())));
    }
    private Prompt prompt(String id, ToolCallback callback) {
        return new Prompt("question", OpenAiChatOptions.builder().reasoningEffort("low").toolCallbacks(callback)
            .toolContext(Map.of(ChatMemory.CONVERSATION_ID, id, "conversationId", id, "userMessage", "Save Zermatt")).build());
    }
    private NamsChatMemory memory(MemoryServiceStub stub) {
        return new NamsChatMemory(stub.client(), WAIT, new NamsContextFormatter(), new NamsToolRecorder(stub.client(), WAIT));
    }
    private List<MemoryServiceStub.RecordedRequest> requests(MemoryServiceStub stub, String path) {
        return stub.requests().stream().filter(r -> r.path().equals(path)).toList();
    }
    @Test void sameNamePartialRoundRetainsBothAttempts() {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed("partial", List.of()).id().toString();
            RuntimeException originalFailure = new IllegalStateException("private"); AtomicInteger thirdToolInvocations = new AtomicInteger();
            var memory = spy(memory(stub));
            var tool = callback(false, (input, context) -> {
                if (input.equals("{\"n\":2}")) throw originalFailure;
                if (input.equals("{\"n\":3}")) thirdToolInvocations.incrementAndGet();
                return "result";
            });
            var manager = new NamsToolCallingManager(ToolCallingManager.builder().build(), memory, mapper);
            assertSame(originalFailure, assertThrows(RuntimeException.class, () -> manager.executeToolCalls(prompt(id, tool), response(null, "{\"n\":1}", "{\"n\":2}", "{\"n\":3}"))));
            ArgumentCaptor<List<NewToolCall>> capture = ArgumentCaptor.captor();
            verify(memory, times(1)).recordToolRound(eq(id), capture.capture());
            List<NewToolCall> attempts = capture.getValue();
            assertEquals(List.of(ToolCallStatus.SUCCESS, ToolCallStatus.ERROR), attempts.stream().map(NewToolCall::status).toList());
            assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), attempts.stream().map(NewToolCall::input).toList());
            stub.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 2, WAIT);
            assertEquals(1, requests(stub, "/v1/reasoning/steps").size()); assertEquals(2, requests(stub, "/v1/reasoning/tool-calls").size());
            assertEquals(0, thirdToolInvocations.get());
        }
    }
    @Test void configuredDelegateLimitsKeepOnlyExecutedAttempts() {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed("limited", List.of()).id().toString(); AtomicInteger invocations = new AtomicInteger();
            var manager = new NamsToolCallingManager(ToolCallingManager.builder().maxCallsPerTool(1).build(), memory(stub), mapper);
            assertThrows(ToolCallLimitExceededException.class, () -> manager.executeToolCalls(prompt(id, callback(false, (input, context) -> { invocations.incrementAndGet(); return "result"; })), response(null, "{\"n\":1}", "{\"n\":2}")));
            stub.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 1, WAIT);
            assertEquals(1, invocations.get()); assertEquals(1, requests(stub, "/v1/reasoning/tool-calls").size());
            assertEquals("{\"n\":1}", requests(stub, "/v1/reasoning/tool-calls").getFirst().body().path("input").asText());
        }
    }
    @Test void selectsFirstToolBearingGenerationForRequiredIntermediateText() {
        var memory = mock(NamsChatMemory.class); var manager = new NamsToolCallingManager(ToolCallingManager.builder().build(), memory, mapper);
        var selected = response("I will check", "{}").getResult();
        var alternatives = new ChatResponse(List.of(new Generation(new AssistantMessage("alternative")), selected, response("other intermediate", "{}").getResult()));
        manager.executeToolCalls(prompt("id", callback(false, (input, context) -> "result")), alternatives);
        verify(memory).add(eq("id"), argThat((List<Message> batch) -> batch.getFirst().getText().equals("I will check")));
        verify(memory, times(2)).add(eq("id"), anyList());
    }
    @Test void completedRoundUsesAddOnceAndOriginalProtocolRemainsUntouched() {
        NamsChatMemory memory = mock(NamsChatMemory.class);
        var manager = new NamsToolCallingManager(ToolCallingManager.builder().build(), memory, mapper);
        String id = java.util.UUID.randomUUID().toString();
        var tool = callback(false, (input, context) -> "result");
        var result = manager.executeToolCalls(prompt(id, tool), response(null, "{}", "{}"));
        verify(memory, times(1)).add(eq(id), argThat((List<Message> batch) -> batch.size() == 1 && batch.getFirst() instanceof ToolResponseMessage));
        verify(memory, never()).recordToolRound(anyString(), anyList());
        var actualModelToolResponse = result.conversationHistory().getLast();
        assertFalse(actualModelToolResponse.getMetadata().containsKey(NamsChatMemory.TOOL_CALLS_METADATA_KEY));
    }
    @Test void intermediateTextFailurePreventsSideEffects() {
        NamsChatMemory memory = mock(NamsChatMemory.class); AtomicInteger toolInvocations = new AtomicInteger();
        doThrow(new MemoryClientException("append failed")).when(memory).add(anyString(), anyList());
        var manager = new NamsToolCallingManager(ToolCallingManager.builder().build(), memory, mapper);
        assertThrows(MemoryClientException.class, () -> manager.executeToolCalls(prompt("id", callback(false, (input, context) -> { toolInvocations.incrementAndGet(); return "result"; })), response("I will check", "{}")));
        assertEquals(0, toolInvocations.get()); verify(memory, never()).recordToolRound(anyString(), anyList());
    }
    @Test void copiedOptionsPreserveProviderFieldsAndPermissions() {
        NamsChatMemory memory = mock(NamsChatMemory.class); AtomicReference<Prompt> observed = new AtomicReference<>(); AtomicReference<ToolContext> observedContext = new AtomicReference<>();
        ToolCallingManager real = ToolCallingManager.builder().build();
        ToolCallingManager delegate = new ToolCallingManager() {
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) { return real.resolveToolDefinitions(options); }
            public org.springframework.ai.model.tool.ToolExecutionResult executeToolCalls(Prompt p, ChatResponse r) { observed.set(p); return real.executeToolCalls(p, r); }
        };
        var tool = callback(false, (input, context) -> { observedContext.set(context); return "result"; });
        var original = prompt("id", tool); var manager = new NamsToolCallingManager(delegate, memory, mapper);
        assertEquals(real.resolveToolDefinitions((ToolCallingChatOptions) original.getOptions()), manager.resolveToolDefinitions((ToolCallingChatOptions) original.getOptions()));
        manager.executeToolCalls(original, response(null, " "));
        assertInstanceOf(OpenAiChatOptions.class, observed.get().getOptions());
        assertEquals("low", ((OpenAiChatOptions) observed.get().getOptions()).getReasoningEffort());
        assertEquals(((ToolCallingChatOptions) original.getOptions()).getToolContext(), observedContext.get().getContext());
        assertSame(tool, ((ToolCallingChatOptions) original.getOptions()).getToolCallbacks().getFirst());
        verify(memory).add(eq("id"), argThat((List<Message> batch) -> {
            @SuppressWarnings("unchecked") List<NewToolCall> attempts = (List<NewToolCall>) batch.getFirst().getMetadata().get(NamsChatMemory.TOOL_CALLS_METADATA_KEY);
            return attempts.getFirst().input().equals("{}") && attempts.stream().allMatch(a -> a.duration() != null && !a.duration().isNegative());
        }));
    }
    @Test void recordingFailureDoesNotDispatchAgainOrReplaceExecutionOutcome() {
        NamsChatMemory memory = mock(NamsChatMemory.class); doThrow(new IllegalStateException("secret")).when(memory).add(anyString(), anyList());
        var manager = new NamsToolCallingManager(ToolCallingManager.builder().build(), memory, mapper);
        assertEquals(3, manager.executeToolCalls(prompt("id", callback(false, (input, context) -> "result")), response(null, "{}")).conversationHistory().size());
        verify(memory, times(1)).add(anyString(), anyList()); verify(memory, never()).recordToolRound(anyString(), anyList());
        RuntimeException original = new IllegalStateException("tool failed");
        doThrow(new IllegalArgumentException("record failed")).when(memory).recordToolRound(anyString(), anyList());
        assertSame(original, assertThrows(RuntimeException.class, () -> manager.executeToolCalls(prompt("id", callback(false, (input, context) -> { throw original; })), response(null, "{}"))));
        verify(memory, times(1)).recordToolRound(anyString(), anyList());
    }
    @Test void convertedExceptionIsRecordedAsErrorAndNormalErrorTextIsSuccess() {
        for (boolean throwsException : List.of(true, false)) try (var stub = new MemoryServiceStub()) {
            var nativeConversation = stub.seed("converted", List.of()); var memory = memory(stub);
            AtomicInteger modelCalls = new AtomicInteger(); AtomicReference<Prompt> modelSaw = new AtomicReference<>();
            var tool = callback(false, (input, context) -> {
                if (throwsException) throw new ToolExecutionException(ToolDefinition.builder().name("probe").description("probe").inputSchema("{}").build(), new IllegalArgumentException("converted error"));
                return "error: unavailable";
            });
            ChatModel model = model(p -> { modelSaw.set(p); return modelCalls.getAndIncrement() == 0 ? response(null, "{}") : response("answer"); });
            var client = ChatClient.builder(model).defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(new NamsToolCallingManager(ToolCallingManager.builder().build(), memory, mapper)).build()).build();
            assertEquals("answer", client.prompt(prompt(nativeConversation.id().toString(), tool)).call().content());
            stub.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 1, WAIT);
            var attempt = requests(stub, "/v1/reasoning/tool-calls").getFirst().body();
            assertEquals(throwsException ? "error" : "success", attempt.path("status").asText());
            assertTrue(modelSaw.get().toString().contains(throwsException ? "converted error" : "error: unavailable"));
            assertEquals(1, requests(stub, "/v1/reasoning/steps").size());
        }
    }
    @Test void directReturnHasNoExtraModelRound() {
        try (var stub = new MemoryServiceStub()) {
            var conversation = stub.seed("direct", List.of()); AtomicInteger calls = new AtomicInteger();
            ChatModel model = model(p -> { calls.incrementAndGet(); return response(null, "{}"); });
            var client = ChatClient.builder(model).defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(new NamsToolCallingManager(ToolCallingManager.builder().build(), memory(stub), mapper)).build()).build();
            assertEquals("direct answer", client.prompt(prompt(conversation.id().toString(), callback(true, (input, context) -> "direct answer"))).call().content());
            stub.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 1, WAIT);
            assertEquals(1, calls.get()); assertEquals(1, requests(stub, "/v1/reasoning/steps").size());
        }
    }
    @Test void overlappingSameIdTurnsKeepIndependentGroups() throws Exception {
        try (var stub = new MemoryServiceStub()) {
            String id = stub.seed("overlap", List.of()).id().toString(); CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            var tool = callback(false, (input, context) -> {
                if (input.equals("{\"n\":1}") || input.equals("{\"n\":3}")) { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
                return "result";
            });
            var manager = new NamsToolCallingManager(ToolCallingManager.builder().build(), memory(stub), mapper);
            var first = CompletableFuture.supplyAsync(() -> manager.executeToolCalls(prompt(id, tool), response(null, "{\"n\":1}", "{\"n\":2}")));
            var second = CompletableFuture.supplyAsync(() -> manager.executeToolCalls(prompt(id, tool), response(null, "{\"n\":3}", "{\"n\":4}")));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            stub.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 4, WAIT);
            Map<String, Set<String>> inputsByStep = new java.util.HashMap<>();
            for (var request : requests(stub, "/v1/reasoning/tool-calls")) inputsByStep.computeIfAbsent(request.body().path("stepId").asText(), key -> new HashSet<>()).add(request.body().path("input").asText());
            assertEquals(Set.of(Set.of("{\"n\":1}", "{\"n\":2}"), Set.of("{\"n\":3}", "{\"n\":4}")), new HashSet<>(inputsByStep.values()));
        }
    }
}
