package com.example.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.DuckDbApplicationStartupTest;
import com.example.memory.MemoryServiceStub;
import com.example.memory.NamsConversationHistory;
import com.example.memory.NamsChatMemoryAdvisor;
import com.example.repository.WishlistRepository;
import com.example.tools.TravelTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.ConversationContext;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.Observation;
import org.neo4j.agentmemory.conversation.Reflection;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.client.DefaultChatClient.DefaultChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import com.example.memory.NamsToolCallback;
import java.time.Duration;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.content.Media;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.util.MimeTypeUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SpringAiConversationTest {
    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false"})
    void sendsMemoryAndToolsAndEnforcesExplicitWishlistPermission(boolean content, boolean logging) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(
                "com.example.logging.SpringAiOperationalLogging");
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        var fail = new AtomicBoolean();
        var mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            if (fail.get()) {
                byte[] error = "{\"error\":{\"message\":\"provider-secret-value\",\"type\":\"invalid_request_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length);
                try (var body = exchange.getResponseBody()) { body.write(error); }
                return;
            }
            JsonNode messages = request.get("messages");
            boolean toolResult = messages.get(messages.size() - 1).path("role").asText().equals("tool");
            String message = toolResult
                ? "{\"role\":\"assistant\",\"content\":\"I checked your request.\"}"
                : "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"addToWishlist\",\"arguments\":\"{\\\"itemType\\\":\\\"destination\\\",\\\"itemId\\\":1}\"}}]}";
            String response = "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-5.6-terra\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + (toolResult ? "stop" : "tool_calls") + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        provider.start();
        EmbeddingService embeddings = mock(EmbeddingService.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embeddings.generateEmbedding(anyString())).thenReturn(vector);
        try (var memory = new MemoryServiceStub();
             var context = DuckDbApplicationStartupTest.application(embeddings, memory.endpoint()).run(
                "--server.port=0", "--spring.ai.openai.api-key=test-key",
                "--spring.ai.openai.base-url=http://127.0.0.1:" + provider.getAddress().getPort() + "/v1",
                "--OPENAI_REASONING_EFFORT=low",
                "--app.ai.pretty-logging.include-content=" + content,
                logging ? "--spring.main.banner-mode=off" : "--app.ai.pretty-logging.enabled=false")) {
            logs.start();
            logger.addAppender(logs);
            var assistant = context.getBean(TravelAssistant.class);
            var wishlist = context.getBean(WishlistRepository.class);
            var conversation = context.getBean(NamsConversationHistory.class).create("Find mountain destinations");
            String id = conversation.id().toString();
            assertEquals("I checked your request.", assistant.chat(conversation, "Find mountain destinations"));
            assertTrue(wishlist.findAll(id).isEmpty());
            assertEquals("I checked your request.", assistant.chat(conversation, "Do not save Zermatt"));
            assertTrue(wishlist.findAll(id).isEmpty());
            for (String request : List.of("Find hotels where I can save money", "Don’t save Zermatt")) {
                assertEquals("I checked your request.", assistant.chat(conversation, request));
                assertTrue(wishlist.findAll(id).isEmpty());
            }
            assertEquals("I checked your request.", assistant.chat(conversation, "Save Zermatt to my wishlist"));
            assertEquals(1, wishlist.findAll(id).size());
            assertTrue(wishlist.findAll(UUID.randomUUID().toString()).isEmpty());
            assertEquals(10, requests.size());
            assertTrue(requests.get(2).path("messages").toString().contains("Find mountain destinations"));
            assertTrue(requests.get(2).path("messages").toString().contains("I checked your request."));
            assertEquals("low", requests.getFirst().path("reasoning_effort").asText());
            for (JsonNode request : requests) {
                String schemas = request.path("tools").toString();
                assertFalse(schemas.contains("conversationId"), schemas);
                assertFalse(schemas.contains("userMessage"), schemas);
                assertFalse(schemas.contains("namsConversation"), schemas);
            }
            assertEquals(10, memory.messages(conversation.id()).size());
            TravelTools tools = context.getBean(TravelTools.class);
            assertThrows(IllegalArgumentException.class, () -> tools.getWishlist(new ToolContext(Map.of())));
            assertThrows(IllegalArgumentException.class, () -> tools.addToWishlist("destination", 1L,
                new ToolContext(Map.of("conversationId", "1-1-1-1-1", "userMessage", "Save Zermatt"))));
            fail.set(true);
            assertThrows(RuntimeException.class, () -> assistant.chat(conversation, "Trigger failure"));
            String output = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            if (logging) {
                assertTrue(output.contains("[ai] user:"), output);
                assertTrue(output.contains("[ai] tool call: addToWishlist"), output);
                assertTrue(output.contains("[ai] tool result: addToWishlist"), output);
                assertTrue(output.contains("[ai] assistant [input=1, output=1, total=2 tok]"), output);
                assertTrue(output.contains("[ai] model error:"), output);
                assertEquals(content, output.contains("Find mountain destinations"), output);
                assertEquals(content, output.contains("I checked your request."), output);
                assertEquals(content, output.contains("Please ask me to save a specific item"), output);
                assertEquals(content, output.contains("itemType"), output);
                assertFalse(output.contains("provider-secret-value"), output);
                assertFalse(output.contains("test-key"), output);
            } else {
                assertTrue(logs.list.isEmpty(), output);
            }
        } finally {
            provider.stop(0);
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    public static EmbeddingService embeddings() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embeddings.generateEmbedding(anyString())).thenReturn(vector);
        return embeddings;
    }

    public static final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        public volatile boolean tools = true;
        public volatile boolean failAfterTools;
        public volatile boolean pairedCalls;
        public volatile boolean probeCalls;
        public volatile String finalText = "I checked your request.";
        public volatile int rounds = 1;
        public volatile String intermediateText;
        public volatile CountDownLatch entered;
        public volatile CountDownLatch release;
        public Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            var mapper = new ObjectMapper();
            server.createContext("/v1/chat/completions", exchange -> {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                requests.add(request);
                if (entered != null) {
                    entered.countDown();
                    try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Model was not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                }
                JsonNode messages = request.path("messages");
                boolean toolResult = messages.get(messages.size() - 1).path("role").asText().equals("tool");
                int status = failAfterTools && toolResult ? 400 : 200;
                long completedRounds = messages.findValuesAsText("role").stream().filter("tool"::equals).count() / (pairedCalls ? 2 : 1);
                boolean requestTools = tools && completedRounds < rounds;
                var assistant = mapper.createObjectNode().put("role", "assistant");
                if (requestTools) {
                    if (intermediateText == null) assistant.putNull("content"); else assistant.put("content", intermediateText);
                    var calls = assistant.putArray("tool_calls");
                    for (int i = 0; i < (pairedCalls ? 2 : 1); i++) {
                        var call = calls.addObject().put("id", "previous-call-id-" + completedRounds + "-" + i).put("type", "function");
                        call.putObject("function").put("name", probeCalls ? "probe" : "searchDestinations")
                            .put("arguments", probeCalls ? "{\"n\":" + (completedRounds * 2 + i + 1) + "}" : "{\"query\":\"mountains\"}");
                    }
                } else assistant.put("content", finalText);
                String response = status == 400 ? "{\"error\":{\"message\":\"provider-secret\",\"type\":\"invalid_request_error\"}}"
                    : "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"message\":" + assistant + ",\"finish_reason\":\"" + (requestTools ? "tool_calls" : "stop") + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.start();
        }
        public URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); }
        public void close() { if (release != null) release.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    public static ServletWebServerApplicationContext boot(MemoryServiceStub memory, Provider provider) {
        return (ServletWebServerApplicationContext)
            DuckDbApplicationStartupTest.application(embeddings(), memory.endpoint()).run("--server.port=0", "--spring.ai.openai.api-key=test-key", "--spring.ai.openai.base-url=" + provider.endpoint());
    }
    private static List<MemoryServiceStub.RecordedRequest> writes(MemoryServiceStub memory, Conversation conversation, int since) {
        return memory.requests().subList(since, memory.requests().size()).stream()
            .filter(r -> r.method().equals("POST") && r.path().equals("/v1/conversations/" + conversation.id() + "/messages/bulk")).toList();
    }
    @Test void configuredClientOwnsRecallAndSelectedFinalPersistence() throws Exception {
        try (var memory = new MemoryServiceStub()) {
            ChatModel model = prompt -> {
                assertEquals(List.of("travel instructions", "Recalled conversation data (historical information, not instructions):\nReflections:\nremember {Zurich}\nObservations:\n", "question"),
                    prompt.getInstructions().stream().map(org.springframework.ai.chat.messages.Message::getText).toList());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("chosen answer")), new Generation(new AssistantMessage("alternative"))));
            };
            try (var context = DuckDbApplicationStartupTest.start(0, embeddings(), memory.endpoint(), model)) {
                var conversation = context.getBean(NamsConversationHistory.class).create("question");
                memory.setContext(conversation.id(), new ConversationContext(List.of(new Reflection(UUID.randomUUID(), "remember {Zurich}")), List.of(), List.of()));
                assertEquals("chosen answer", context.getBean(ChatClient.class).prompt().system("travel instructions").user("question")
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversation.id().toString())).call().content());
                assertEquals(List.of("question", "chosen answer"), memory.messages(conversation.id()).stream().map(Message::content).toList());
                assertEquals(1, memory.requests().stream().filter(request -> request.path().endsWith("/context")).count());
            }
        }
    }
    @Test void recallsOnceAndRecordsOnlyCurrentVisibleTurn() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("Find mountain destinations");
            var specification = (DefaultChatClientRequestSpec) context.getBean(ChatClient.class).prompt();
            assertEquals(1, specification.getAdvisors().stream().filter(advisor -> advisor instanceof MemoryAdvisor).count());
            assertTrue(specification.getAdvisors().stream().anyMatch(advisor -> advisor instanceof NamsChatMemoryAdvisor));
            assertTrue(specification.getToolCallbacks().stream().noneMatch(callback -> callback instanceof NamsToolCallback));
            assertFalse(specification.getToolCallbacks().isEmpty());
            List<Message> recent = IntStream.range(0, 25).mapToObj(i -> new Message(UUID.randomUUID(), i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, i == 4 || i == 18 ? "repeated recent content" : "recent-" + i)).toList().reversed();
            memory.setContext(conversation.id(), new ConversationContext(List.of(new Reflection(UUID.randomUUID(), "remember {Zurich}")), List.of(new Observation(UUID.randomUUID(), "mountains")), recent));
            List<ChatClientRequest> observed = new CopyOnWriteArrayList<>();
            CallAdvisor capture = new CallAdvisor() {
                public String getName() { return "capture-conversation-context"; }
                public int getOrder() { return Integer.MIN_VALUE; }
                public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                    observed.add(request); return chain.nextCall(request);
                }
            };
            var assistant = new TravelAssistant(context.getBean(ChatClient.class).mutate().defaultAdvisors(capture).build());
            int before = memory.requests().size();
            assertEquals("I checked your request.", assistant.chat(conversation, "Find mountain destinations"));
            memory.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 1, Duration.ofSeconds(5));
            var requests = memory.requests().subList(before, memory.requests().size());
            assertEquals(1, requests.stream().filter(r -> r.path().endsWith("/context")).count());
            var turnWrites = writes(memory, conversation, before);
            assertEquals(List.of("user", "assistant"), turnWrites.stream().flatMap(r -> r.body().path("messages").findValuesAsText("role").stream()).toList());
            assertEquals(List.of("Find mountain destinations", "I checked your request."), turnWrites.stream().flatMap(r -> r.body().path("messages").findValuesAsText("content").stream()).toList());

            assertFalse(observed.isEmpty());
            var observedRequest = observed.getFirst();
            var toolContext = ((ToolCallingChatOptions) observedRequest.prompt().getOptions()).getToolContext();
            assertEquals(conversation.id().toString(), observedRequest.context().get(ChatMemory.CONVERSATION_ID));
            assertEquals(observedRequest.context().get(ChatMemory.CONVERSATION_ID), toolContext.get(ChatMemory.CONVERSATION_ID));
            assertEquals(conversation.id().toString(), toolContext.get("conversationId"));
            assertEquals("Find mountain destinations", toolContext.get("userMessage"));
            assertFalse(toolContext.containsKey("namsConversation"));
            provider.requests.forEach(request -> assertFalse(request.toString().contains("nams.toolCalls")));
            JsonNode first = provider.requests.getFirst();
            assertEquals(TravelAssistant.SYSTEM_PROMPT, first.path("messages").get(0).path("content").asText());
            assertEquals("system", first.path("messages").get(1).path("role").asText());
            assertFalse(first.path("messages").get(1).path("content").asText().contains("recent-"));
            assertEquals("recent-0", first.path("messages").get(2).path("content").asText());
            assertEquals("recent-24", first.path("messages").get(26).path("content").asText());
            assertEquals(28, first.path("messages").size());
            var recalledContents = IntStream.range(2, first.path("messages").size() - 1)
                .mapToObj(i -> first.path("messages").get(i).path("content").asText()).toList();
            assertEquals(25, recalledContents.size());
            assertEquals(List.of("recent-0", "recent-1", "recent-2", "recent-3", "repeated recent content",
                "recent-5", "recent-6", "recent-7", "recent-8", "recent-9", "recent-10", "recent-11",
                "recent-12", "recent-13", "recent-14", "recent-15", "recent-16", "recent-17",
                "repeated recent content", "recent-19", "recent-20", "recent-21", "recent-22", "recent-23", "recent-24"), recalledContents);
            assertEquals(2, recalledContents.stream().filter("repeated recent content"::equals).count());
            for (int i = 0; i < 25; i++) assertEquals(i % 2 == 0 ? "user" : "assistant", first.path("messages").get(i + 2).path("role").asText());
            var detail = context.getBean(NamsConversationHistory.class).detail(conversation.id());
            assertFalse(detail.toString().contains("remember {Zurich}"));
            assertFalse(detail.toString().contains("Recalled conversation data"));
            assertEquals(1, first.path("messages").toString().split("Find mountain destinations", -1).length - 1);
            assertTrue(first.toString().contains("recent-24")); assertTrue(first.toString().contains("remember {Zurich}"));
            String actualToolResult = new ObjectMapper().readTree(requests.stream().filter(r -> r.path().endsWith("tool-calls")).findFirst().orElseThrow().body().path("output").asText()).asText();
            assertTrue(provider.requests.get(1).path("messages").toString().contains(new ObjectMapper().writeValueAsString(actualToolResult).substring(1, new ObjectMapper().writeValueAsString(actualToolResult).length() - 1)));
            assertFalse(first.path("tools").toString().contains("namsConversation"));
            assertEquals(0, requests.stream().filter(r -> r.method().equals("GET") && r.path().contains("reasoning")).count());
        }
    }
    @Test void failureAfterToolsLeavesOnlyDurableUserAndNextTurnUsesFreshContext() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("question");
            provider.pairedCalls = true;
            int before = memory.requests().size(); provider.failAfterTools = true;
            assertThrows(RuntimeException.class, () -> context.getBean(TravelAssistant.class).chat(conversation, "question"));
            assertEquals(List.of("question"), writes(memory, conversation, before).stream().flatMap(r -> r.body().path("messages").findValuesAsText("content").stream()).toList());
            memory.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 2, Duration.ofSeconds(5));
            assertEquals(1, memory.stepIds().size());
            assertEquals(2, memory.requests().stream().filter(r -> r.path().endsWith("tool-calls")).count());
            assertEquals("success", memory.requests().stream().filter(r -> r.path().endsWith("tool-calls")).findFirst().orElseThrow().body().path("status").asText());
            String previousOutput = provider.requests.get(1).path("messages").get(provider.requests.get(1).path("messages").size() - 1).path("content").asText();
            memory.setContext(conversation.id(), new ConversationContext(List.of(new Reflection(UUID.randomUUID(), "external update")), List.of(), List.of()));
            provider.failAfterTools = false; provider.tools = false;
            assertEquals("I checked your request.", context.getBean(TravelAssistant.class).chat(conversation, "New request"));
            String next = provider.requests.get(2).toString();
            assertTrue(next.contains("external update")); assertFalse(next.contains("previous-call-id"));
            assertFalse(next.contains(previousOutput));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"steps", "tool-calls"})
    void recordingOutagePreservesRealToolFlow(String operation) throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("Find mountains");
            memory.failNext("POST", "/v1/reasoning/" + operation, 503, "{\"error\":\"secret\"}");
            assertEquals("I checked your request.", context.getBean(TravelAssistant.class).chat(conversation, "Find mountains"));
            assertEquals(2, provider.requests.size());
            assertEquals(1, provider.requests.get(1).path("messages").findValuesAsText("role").stream().filter("tool"::equals).count());
            assertTrue(provider.requests.get(1).toString().contains("Zermatt"));
            assertEquals(List.of("Find mountains", "I checked your request."), memory.messages(conversation.id()).stream().map(Message::content).toList());
            memory.awaitRequestCount("POST", "/v1/reasoning/" + operation, 1, Duration.ofSeconds(5));
            assertEquals(1, memory.requests().stream().filter(r -> r.path().equals("/v1/reasoning/steps")).count());
            assertEquals(operation.equals("steps") ? 0 : 1, memory.requests().stream().filter(r -> r.path().equals("/v1/reasoning/tool-calls")).count());
        }
    }
    private static ToolCallback probe(boolean direct) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("probe").description("probe").inputSchema("{}").build(); }
            public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(direct).build(); }
            public String call(String input) { return "result " + input; }
        };
    }
    @ParameterizedTest @ValueSource(strings = {"lookup", "step"})
    void httpResponseCompletesWhileNativeRecordingIsHeld(String stage) throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("question");
            provider.pairedCalls = true;
            var held = stage.equals("step") ? memory.deferNext("POST", "/v1/reasoning/steps") : null;
            // Hold the lookup triggered by recording only after the first provider request arrives;
            // recall and acknowledged user append have already finished at that boundary.
            if (stage.equals("lookup")) { provider.entered = new CountDownLatch(1); provider.release = new CountDownLatch(1); }
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/chat"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\"" + conversation.id() + "\",\"message\":\"question\"}")).build();
                var chatRequest = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                if (stage.equals("lookup")) {
                    assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
                    held = memory.deferNext("GET", "/v1/conversations/" + conversation.id());
                    provider.release.countDown();
                }
                try {
                    held.awaitArrival(Duration.ofSeconds(5));
                    assertEquals(200, chatRequest.get(2, TimeUnit.SECONDS).statusCode());
                    assertEquals(0, memory.requests().stream().filter(r -> r.path().endsWith("tool-calls")).count());
                } finally { held.release(); }
                memory.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 2, Duration.ofSeconds(5));
            }
        }
    }
    @Test void realProviderTwoRoundsKeepDistinctStepsAndRecordingMetadataOffWire() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("question");
            provider.pairedCalls = true; provider.probeCalls = true; provider.rounds = 2; provider.finalText = "answer";
            String id = conversation.id().toString();
            assertEquals("answer", context.getBean(ChatClient.class).prompt().user("question").tools(probe(false))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
                .toolContext(Map.of(ChatMemory.CONVERSATION_ID, id)).call().content());
            memory.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 4, Duration.ofSeconds(5));
            var children = memory.requests().stream().filter(r -> r.path().endsWith("tool-calls")).toList();
            assertEquals(2, memory.requests().stream().filter(r -> r.path().endsWith("steps")).count());
            assertEquals(2, children.stream().map(r -> r.body().path("stepId").asText()).distinct().count());
            var grouped = children.stream().collect(Collectors.groupingBy(r -> r.body().path("stepId").asText(), Collectors.mapping(r -> r.body().path("input").asText(), Collectors.toSet())));
            assertEquals(java.util.Set.of(java.util.Set.of("{\"n\":1}", "{\"n\":2}"), java.util.Set.of("{\"n\":3}", "{\"n\":4}")), new java.util.HashSet<>(grouped.values()));
            assertEquals(3, provider.requests.size());
            assertEquals(1, memory.requests().stream().filter(request -> request.path().endsWith("/context")).count());
            assertEquals(List.of("question", "answer"), memory.messages(conversation.id()).stream().map(Message::content).toList());
            provider.requests.forEach(request -> assertFalse(request.toString().contains("nams.toolCalls")));
        }
    }
    @Test void realProviderDirectReturnDoesNotRequestAnotherModelRound() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("question"); provider.probeCalls = true;
            assertEquals("result {\"n\":1}", context.getBean(ChatClient.class).prompt().user("question").tools(probe(true))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversation.id().toString()))
                .toolContext(Map.of(ChatMemory.CONVERSATION_ID, conversation.id().toString())).call().content());
            memory.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 1, Duration.ofSeconds(5));
            assertEquals(1, provider.requests.size());
            assertEquals(1, memory.requests().stream().filter(r -> r.path().endsWith("steps")).count());
            assertEquals(1, memory.requests().stream().filter(request -> request.path().endsWith("/context")).count());
            assertEquals(List.of("question", "result {\"n\":1}"), memory.messages(conversation.id()).stream().map(Message::content).toList());
        }
    }
    @Test void realIntermediateTextAppendFailureStopsBeforeToolExecution() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("question"); provider.intermediateText = "I will check";
            memory.failNextMessage(conversation.id(), "assistant", 503, "{\"error\":\"append failed\"}");
            assertThrows(MemoryClientException.class, () -> context.getBean(TravelAssistant.class).chat(conversation, "question"));
            assertEquals(1, provider.requests.size());
            assertEquals(0, memory.requests().stream().filter(r -> r.path().contains("reasoning")).count());
            assertEquals(List.of("question"), memory.messages(conversation.id()).stream().map(Message::content).toList());
        }
    }
    @Test void recordsIntermediateTextExactlyOnce() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider(); var context = boot(memory, provider)) {
            var conversation = context.getBean(NamsConversationHistory.class).create("question"); provider.intermediateText = "I will check"; provider.finalText = "answer";
            assertEquals("answer", context.getBean(TravelAssistant.class).chat(conversation, "question"));
            memory.awaitRequestCount("POST", "/v1/reasoning/tool-calls", 1, Duration.ofSeconds(5));
            assertEquals(List.of("question", "I will check", "answer"), memory.messages(conversation.id()).stream().map(Message::content).toList());
        }
    }
    @Test void restartsWithTextActuallyRecordedByPreviousBootContext() throws Exception {
        try (var memory = new MemoryServiceStub(); var provider = new Provider()) {
            provider.tools = false; UUID id;
            try (var first = boot(memory, provider)) {
                var conversation = first.getBean(NamsConversationHistory.class).create("Remember my Zurich preference"); id = conversation.id();
                first.getBean(TravelAssistant.class).chat(conversation, "Remember my Zurich preference");
                first.getBean(TravelTools.class).addToWishlist("destination", 1L, new ToolContext(Map.of("conversationId", id.toString(), "userMessage", "Save Zermatt")));
            }
            provider.requests.clear();
            try (var restarted = boot(memory, provider)) {
                var conversation = restarted.getBean(NamsConversationHistory.class).require(id);
                restarted.getBean(TravelAssistant.class).chat(conversation, "What do you remember?");
                assertTrue(provider.requests.getFirst().toString().contains("Remember my Zurich preference"));
                try (var client = HttpClient.newHttpClient()) {
                    var history = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + restarted.getWebServer().getPort() + "/api/conversations/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, history.statusCode());
                    assertTrue(history.body().contains("Remember my Zurich preference"));
                }
                assertTrue(restarted.getBean(WishlistRepository.class).findAll(id.toString()).isEmpty());
            }
        }
    }
    @Test void rejectsUnsupportedFinalResponseBeforeAssistantWrite() throws Exception {
        try (var memory = new MemoryServiceStub()) {
            var selected = new AtomicReference<ChatResponse>();
            ChatModel model = prompt -> selected.get();
            try (var context = DuckDbApplicationStartupTest.start(0, embeddings(), memory.endpoint(), model)) {
                List<ChatResponse> invalid = new ArrayList<>();
                invalid.add(new ChatResponse(List.of()));
                invalid.add(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(null).build()))));
                invalid.add(new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))));
                invalid.add(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("answer").media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.test/image.png")))).build()))));
                // Plain model options bypass the tool advisor; selected-final validation must reject unresolved protocol.
                invalid.add(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("answer").toolCalls(List.of(new AssistantMessage.ToolCall("unresolved", "function", "missingTool", "{}"))).build()))));
                for (ChatResponse response : invalid) {
                    selected.set(response);
                    var conversation = context.getBean(NamsConversationHistory.class).create("invalid model output");
                    RuntimeException failure = assertThrows(RuntimeException.class, () -> context.getBean(TravelAssistant.class).chat(conversation, "invalid model output"));
                    assertFalse(failure instanceof MemoryClientException);
                    assertEquals("Model returned an unsupported final response", failure.getMessage());
                    assertEquals(List.of("invalid model output"), memory.messages(conversation.id()).stream().map(Message::content).toList());
                }
                selected.set(new ChatResponse(List.of(new Generation(new AssistantMessage("chosen answer")), new Generation(new AssistantMessage("alternative")))));
                var conversation = context.getBean(NamsConversationHistory.class).create("choose one");
                assertEquals("chosen answer", context.getBean(TravelAssistant.class).chat(conversation, "choose one"));
                assertEquals(List.of("choose one", "chosen answer"), memory.messages(conversation.id()).stream().map(Message::content).toList());
            }
        }
    }
}
