package com.example.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.conversation.NewMessage;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NamsChatMemoryTest {

    @Test
    void multipartUserTextPreservesTheLiveMessageAndPersistsEachPartInOrder() throws Exception {
        try (var fixture = new Fixture()) {
            var user = UserMessage.from(TextContent.from("Visit Zermatt."), TextContent.from("Then Lucerne."));
            fixture.memory.add(user);
            assertEquals(List.of(user), fixture.memory.messages());
            fixture.memory.add(AiMessage.from("Noted."));
            assertRememberedText(fixture.memory.messages(), "Noted.", "Then Lucerne.", "Visit Zermatt.");
            assertEquals(fixture.memory.messages(),
                new NamsChatMemory(fixture.conversation, fixture.configuration).messages());
        }
    }

    @Test
    void failedMultipartAppendPropagatesWithoutRetainingTheLiveMessageOrRetrying() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.nams.appendStatus = 503;
            var user = UserMessage.from(TextContent.from("Visit Zermatt."), TextContent.from("Then Lucerne."));
            assertEquals(503, assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(user)).statusCode());
            assertEquals(List.of(), fixture.memory.messages());
            assertEquals(2, fixture.nams.appends);
        }
    }

    @Test
    void recordsToolsButRetainsTheirMessagesOnlyDuringTheActiveTurn() throws Exception {
        try (var nams = new FakeNamsServer()) {
            var configuration = new NamsMemoryConfiguration();
            configuration.setWaitTimeout(Duration.ofSeconds(1));
            var client = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
            var conversation = MemoryClient.await(client.createConversation(
                new CreateConversation(null, Map.of("title", "Save Zermatt"))),
                configuration.getWaitTimeout());
            var memory = new NamsChatMemory(conversation, configuration);
            memory.add(UserMessage.from("Save Zermatt"));
            var request = ToolExecutionRequest.builder().id("call_1")
                .name("addToWishlist").arguments("{\"itemId\":1}").build();
            var result = ToolExecutionResultMessage.builder().id(request.id())
                .toolName(request.name()).text("Saved").isError(false).build();
            memory.add(AiMessage.from(request));
            memory.add(result);
            assertEquals(List.of(UserMessage.from("Save Zermatt"), AiMessage.from(request), result),
                memory.messages());
            memory.add(AiMessage.from("Saved Zermatt."));
            nams.observations.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Zermatt was selected earlier."));
            var completed = memory.messages();
            assertNoToolMessages(completed);
            assertTrue(assertInstanceOf(SystemMessage.class, completed.getFirst())
                .text().contains("Zermatt was selected earlier."));
            var fresh = new NamsChatMemory(conversation, configuration);
            assertEquals(completed, fresh.messages());
            assertEquals(2, nams.appends);
            assertEquals(1, nams.stepWrites);
            assertEquals(1, nams.toolWrites);
            assertEquals(0, nams.traceReads);
        }
    }

    @Test
    void parallelRepeatedRequestsShareOneStepAndRetainDistinctResults() throws Exception {
        try (var fixture = new Fixture()) {
            var memory = fixture.memory;
            var first = request("first");
            var second = request("second");
            var ai = AiMessage.builder().text("Saving both.").thinking("private thinking")
                .toolExecutionRequests(List.of(first, second)).build();
            var secondResult = ToolExecutionResultMessage.from(second, "second output");
            var firstResult = ToolExecutionResultMessage.builder().id(first.id()).toolName(first.name())
                .text("first failed").isError(true).build();
            memory.add(UserMessage.from("Save both"));
            memory.add(ai);
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(0, fixture.nams.toolWrites);
            memory.add(secondResult);
            memory.add(firstResult);
            assertEquals(List.of(UserMessage.from("Save both"), ai, secondResult, firstResult), memory.messages());
            assertEquals(2, fixture.nams.appends);
            var step = new HashMap<>(fixture.nams.steps.getFirst());
            String stepId = step.remove("id").toString();
            assertEquals(Map.of("conversationId", FakeNamsServer.ID,
                "reasoning", "Assistant requested tool execution", "actionTaken", "Call tools: addToWishlist, addToWishlist"), step);
            var call = new HashMap<>(fixture.nams.toolCalls.getFirst());
            call.remove("id");
            assertEquals(Map.of("stepId", stepId, "toolName", "addToWishlist", "input", "{\"itemId\":1}",
                "output", "second output", "status", "success"), call);
            assertEquals(stepId, fixture.nams.toolCalls.getLast().get("stepId"));
            assertEquals("error", fixture.nams.toolCalls.getLast().get("status"));
            memory.add(AiMessage.builder().text("Finished").thinking("also private").build());
            assertRememberedText(memory.messages(), "Finished", "Saving both.", "Save both");
        }
    }

    @Test
    void twoToolRoundsAccumulateOnlyUntilTerminalResponse() throws Exception {
        try (var fixture = new Fixture()) {
            var memory = fixture.memory;
            memory.add(UserMessage.from("Save twice"));
            var expected = new ArrayList<ChatMessage>(List.of(UserMessage.from("Save twice")));
            for (String id : List.of("round1", "round2")) {
                var request = request(id);
                var ai = AiMessage.from(request);
                var result = ToolExecutionResultMessage.from(request, "Saved");
                memory.add(ai);
                memory.add(result);
                expected.add(ai);
                expected.add(result);
                assertEquals(expected, memory.messages());
            }
            assertEquals(2, fixture.nams.stepWrites);
            assertEquals(2, fixture.nams.toolWrites);
            memory.add(AiMessage.builder().thinking("hidden only").build());
            assertRememberedText(memory.messages(), "Save twice");
            assertEquals(1, fixture.nams.appends);
        }
    }

    @Test
    void newUserAndExplicitEndTurnDiscardToolsWithoutTerminalText() throws Exception {
        try (var fixture = new Fixture()) {
            var memory = fixture.memory;
            memory.add(SystemMessage.from("current prompt"));
            fixture.liveToolTurn();
            memory.add(UserMessage.from("Next question"));
            var nextTurn = memory.messages();
            assertEquals(2, nextTurn.size());
            assertRememberedText(nextTurn.subList(0, 1), "Save Zermatt");
            assertEquals(UserMessage.from("Next question"), nextTurn.getLast());
            memory.add(AiMessage.from(request("call_2")));
            memory.endTurn();
            assertNoToolMessages(memory.messages());
            assertTrue(assertInstanceOf(SystemMessage.class, memory.messages().getFirst())
                .text().startsWith("current prompt\n\n"));
            assertRememberedText(memory.messages(), "Next question", "Save Zermatt");
            assertThrows(IllegalArgumentException.class,
                () -> memory.add(ToolExecutionResultMessage.from(request("call_2"), "late")));
            assertEquals(0, fixture.nams.deletes);
        }
    }

    @Test
    void incompleteToolExchangeCanBeReadAndCompletedByRequestId() throws Exception {
        try (var fixture = new Fixture()) {
            var request = request("call_1");
            var result = ToolExecutionResultMessage.from(request, "Saved");
            fixture.memory.add(UserMessage.from("Save Zermatt"));
            fixture.memory.add(AiMessage.from(request));
            assertEquals(List.of(UserMessage.from("Save Zermatt"), AiMessage.from(request)),
                fixture.memory.messages());
            fixture.memory.add(result);
            assertEquals(List.of(UserMessage.from("Save Zermatt"), AiMessage.from(request), result),
                fixture.memory.messages());
            assertEquals(2, fixture.nams.contextReads);
            assertEquals(1, fixture.nams.toolWrites);
        }
    }

    @Test
    void multipleToolRequestMessagesCorrelateResultsById() throws Exception {
        try (var fixture = new Fixture()) {
            var first = request("first");
            var second = request("second");
            var firstAi = AiMessage.from(first);
            var secondAi = AiMessage.from(second);
            var secondResult = ToolExecutionResultMessage.from(second, "second output");
            var firstResult = ToolExecutionResultMessage.from(first, "first output");
            fixture.memory.add(UserMessage.from("Save"));
            fixture.memory.add(firstAi);
            fixture.memory.add(secondAi);
            fixture.memory.add(secondResult);
            fixture.memory.add(firstResult);
            assertEquals(List.of(UserMessage.from("Save"), firstAi, secondAi, secondResult, firstResult),
                fixture.memory.messages());
            assertEquals(2, fixture.nams.stepWrites);
            assertEquals(2, fixture.nams.toolWrites);
        }
    }

    @Test
    void unknownMismatchedAndDuplicateResultsRejectWithoutRecording() throws Exception {
        try (var fixture = new Fixture()) {
            for (var invalid : List.of(ToolExecutionResultMessage.from("unknown", "addToWishlist", "x"),
                    ToolExecutionResultMessage.from("call_1", "anotherTool", "x"))) {
                fixture.memory.add(UserMessage.from("Save"));
                fixture.memory.add(AiMessage.from(request("call_1")));
                assertThrows(IllegalArgumentException.class, () -> fixture.memory.add(invalid));
                assertNoToolMessages(fixture.memory.messages());
            }
            assertEquals(0, fixture.nams.toolWrites);
            fixture.liveToolTurn();
            assertThrows(IllegalArgumentException.class,
                () -> fixture.memory.add(ToolExecutionResultMessage.from(request("call_1"), "duplicate")));
            assertEquals(1, fixture.nams.toolWrites);
            assertNoToolMessages(fixture.memory.messages());
        }
    }

    @Test
    void validatesAllToolRequestsBeforeWritingVisibleTextOrStep() throws Exception {
        try (var fixture = new Fixture()) {
            var invalidRequests = List.of(
                request(" "), request(null), request("call_1"),
                request("bad").toBuilder().name(" ").build(),
                request("bad").toBuilder().name(null).build(),
                request("bad").toBuilder().arguments(null).build());
            for (var invalid : invalidRequests) {
                fixture.memory.add(UserMessage.from("Save"));
                int before = fixture.nams.appends;
                assertThrows(IllegalArgumentException.class, () -> fixture.memory.add(
                    AiMessage.from("Must not be appended", List.of(request("call_1"), invalid))));
                assertEquals(before, fixture.nams.appends);
                assertEquals(0, fixture.nams.stepWrites);
            }
            assertThrows(IllegalStateException.class, () -> fixture.memory.add(AiMessage.from(request("no user"))));
            assertEquals(0, fixture.nams.stepWrites);
        }
    }

    @Test
    void unsupportedContentRejectsBeforeAssociatedWrites() throws Exception {
        try (var fixture = new Fixture()) {
            var invalidMessages = new ArrayList<ChatMessage>();
            invalidMessages.add(null);
            invalidMessages.add(UserMessage.from(TextContent.from("one"), ImageContent.from("https://example.com/image.png")));
            invalidMessages.add(UserMessage.from(ImageContent.from("https://example.com/image.png")));
            invalidMessages.add(AiMessage.builder().text("image caption")
                .attributes(Map.of(AiMessage.GENERATED_IMAGES_KEY, List.of(Image.builder().url("https://example.com/image.png").build())))
                .build());
            invalidMessages.add(CustomMessage.from(Map.of("unsupported", "content")));
            for (var invalid : invalidMessages) {
                assertThrows(IllegalArgumentException.class, () -> fixture.memory.add(invalid));
            }
            assertEquals(0, fixture.nams.appends);
            assertEquals(0, fixture.nams.stepWrites);
            fixture.memory.add(UserMessage.from("Save"));
            fixture.memory.add(AiMessage.from(request("call_1")));
            assertThrows(IllegalArgumentException.class, () -> fixture.memory.add(ToolExecutionResultMessage.builder()
                .id("call_1").toolName("addToWishlist").contents(TextContent.from("one"), TextContent.from("two")).build()));
            assertEquals(0, fixture.nams.toolWrites);
            assertNoToolMessages(fixture.memory.messages());
        }
    }

    @Test
    void sixtyTextTurnsPreserveEveryAppendWhileContextSelectsRecentMessages() throws Exception {
        try (var fixture = new Fixture()) {
            for (int i = 0; i < 60; i++) {
                fixture.memory.add(UserMessage.from("same"));
                fixture.memory.add(AiMessage.from("same reply"));
                assertEquals(1, fixture.memory.messages().size());
            }
            assertEquals(120, fixture.nams.appends);
            assertEquals(0, fixture.nams.messageReads);
            assertEquals(120, MemoryClient.await(fixture.conversation.messages(200), fixture.configuration.getWaitTimeout()).size());
            assertEquals(120, fixture.nams.messages.size());
            assertRememberedText(fixture.memory.messages(),
                IntStream.range(0, 20)
                    .mapToObj(i -> i % 2 == 0 ? "same reply" : "same").toArray(String[]::new));
            assertEquals(61, fixture.nams.contextReads);
            assertEquals(0, fixture.nams.deletes);
        }
    }

    @Test
    void replacementOverloadsRejectWithoutAppendingOrDeleting() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(UnsupportedOperationException.class, () -> fixture.memory.set(UserMessage.from("replacement")));
            assertThrows(UnsupportedOperationException.class, () -> fixture.memory.set(List.of(UserMessage.from("replacement"))));
            assertEquals(0, fixture.nams.appends);
            assertEquals(0, fixture.nams.deletes);
        }
    }

    @Test
    void everyReadRefreshesOnlyContextAndReturnsDefensiveResults() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memory.add(SystemMessage.from("old prompt"));
            fixture.memory.add(SystemMessage.from("current prompt"));
            MemoryClient.await(fixture.conversation.addMessage(NewMessage.user("Question")), fixture.configuration.getWaitTimeout());
            MemoryClient.await(fixture.conversation.addMessage(NewMessage.assistant("Answer")), fixture.configuration.getWaitTimeout());
            assertRememberedText(fixture.memory.messages(), "Answer", "Question");
            MemoryClient.await(fixture.conversation.addMessage(NewMessage.user("External follow-up")), fixture.configuration.getWaitTimeout());
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(), "content", "Likes trains"));
            fixture.nams.reflections.add(Map.of("id", UUID.randomUUID().toString(), "content", "Plan rail journeys"));
            fixture.nams.contextMessages.add(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Earlier preference"));
            var remembered = fixture.memory.messages();
            assertRememberedText(remembered, "External follow-up", "Answer", "Question", "Earlier preference");
            String prompt = assertInstanceOf(SystemMessage.class, remembered.getFirst()).text();
            assertTrue(prompt.startsWith("current prompt"));
            assertTrue(prompt.contains("Likes trains"));
            assertTrue(prompt.contains("Plan rail journeys"));
            assertTrue(prompt.contains("Earlier preference"));
            remembered.clear();
            assertEquals(1, fixture.memory.messages().size());
            fixture.nams.observations.clear();
            assertFalse(assertInstanceOf(SystemMessage.class, fixture.memory.messages().getFirst()).text().contains("Likes trains"));
            assertEquals(0, fixture.nams.messageReads);
            assertEquals(4, fixture.nams.contextReads);
        }
    }

    @Test
    void missingAndNullOptionalContextCollectionsAreValid() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memory.add(UserMessage.from("Question"));
            for (String body : List.of("{}", "{\"reflections\":null,\"observations\":null,\"recentMessages\":null}")) {
                fixture.nams.contextBodyOverride = body;
                assertEquals(List.of(UserMessage.from("Question")), fixture.memory.messages());
            }
            fixture.memory.endTurn();
            assertTrue(fixture.memory.messages().isEmpty());
            assertEquals(1, fixture.nams.messages.size());
            assertEquals(0, fixture.nams.messageReads);
        }
    }

    @Test
    void contextStatusAndDecodeFailuresClearLiveToolMessages() throws Exception {
        for (String body : List.of("status", "{invalid-json", "{\"observations\":[{\"id\":\"invalid-uuid\",\"content\":\"bad\"}]}")) {
            try (var fixture = new Fixture()) {
                fixture.liveToolTurn();
                if (body.equals("status")) {
                    fixture.nams.contextStatus = 500;
                    assertEquals(500, assertThrows(MemoryServiceException.class, fixture.memory::messages).statusCode());
                    fixture.nams.contextStatus = 200;
                } else {
                    fixture.nams.contextBodyOverride = body;
                    assertThrows(MemoryClientException.class, fixture.memory::messages);
                    fixture.nams.contextBodyOverride = null;
                }
                assertNoToolMessages(fixture.memory.messages());
            }
        }
    }

    @Test
    void failedStepRecordingEndsTurnBeforeAnyToolCanBeRecorded() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memory.add(UserMessage.from("Save"));
            fixture.nams.stepStatus = 503;
            assertEquals(503, assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(AiMessage.from(request("call_1")))).statusCode());
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(0, fixture.nams.toolWrites);
            fixture.nams.stepStatus = 200;
            assertThrows(IllegalArgumentException.class,
                () -> fixture.memory.add(ToolExecutionResultMessage.from(request("call_1"), "late")));
            assertNoToolMessages(fixture.memory.messages());
            fixture.liveToolTurn();
            assertEquals(1, fixture.nams.toolWrites);
        }
    }

    @Test
    void failedResultRecordingEndsTurnBeforeModelCanSeeResult() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memory.add(UserMessage.from("Save"));
            fixture.memory.add(AiMessage.from(request("call_1")));
            fixture.nams.toolStatus = 503;
            assertEquals(503, assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(ToolExecutionResultMessage.from(request("call_1"), "Saved"))).statusCode());
            assertEquals(1, fixture.nams.toolWrites);
            fixture.nams.toolStatus = 200;
            assertRememberedText(fixture.memory.messages(), "Save");
            assertThrows(IllegalArgumentException.class,
                () -> fixture.memory.add(ToolExecutionResultMessage.from(request("call_1"), "retry")));
            assertEquals(1, fixture.nams.toolWrites);
        }
    }

    @Test
    void appendFailurePropagatesAndDiscardsLiveToolsWithoutRetry() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.liveToolTurn();
            fixture.nams.appendStatus = 503;
            assertEquals(503, assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(AiMessage.from("Final reply"))).statusCode());
            assertEquals(2, fixture.nams.appends);
            fixture.nams.appendStatus = 200;
            assertRememberedText(fixture.memory.messages(), "Save Zermatt");
            fixture.nams.appendStatus = 404;
            assertEquals(404, assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(UserMessage.from("Missing"))).statusCode());
        }
    }

    @Test
    void clearDeletesNativeActivityAndWishlistsAndToleratesRepeated404() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memory.add(SystemMessage.from("current prompt"));
            fixture.liveToolTurn();
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(), "content", "Delete me"));
            fixture.memory.clear();
            fixture.memory.clear();
            assertTrue(fixture.nams.messages.isEmpty());
            assertTrue(fixture.nams.steps.isEmpty());
            assertTrue(fixture.nams.toolCalls.isEmpty());
            assertTrue(fixture.nams.observations.isEmpty());
            assertEquals(404, assertThrows(MemoryServiceException.class, fixture.memory::messages).statusCode());
            assertEquals(404, assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(UserMessage.from("Continue"))).statusCode());
            MemoryClient.await(fixture.client.createConversation(new CreateConversation(null, Map.of("title", "Recreated"))), fixture.configuration.getWaitTimeout());
            assertEquals(List.of(SystemMessage.from("current prompt")), fixture.memory.messages());
        }
    }

    @Test
    void failedDeletionPreservesWishlistsButAlwaysDiscardsLiveTools() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.liveToolTurn();
            fixture.nams.deleteStatus = 503;
            assertEquals(503, assertThrows(MemoryServiceException.class, fixture.memory::clear).statusCode());
            assertNoToolMessages(fixture.memory.messages());
            fixture.nams.deleteStatus = 404;
            fixture.memory.clear();
        }
    }


    @Test
    void providerReturnsFreshAdaptersSharingNativeHistoryAndContext() throws Exception {
        try (var fixture = new Fixture()) {
            var provider = new NamsChatMemoryProvider(fixture.client, fixture.configuration);
            var id = UUID.fromString(FakeNamsServer.ID);
            var first = provider.get(id);
            var second = provider.get(id.toString().toUpperCase());
            assertNotSame(first, second);
            assertEquals(id, first.id());
            assertEquals(id, second.id());
            first.add(UserMessage.from("Prefer trains"));
            first.add(AiMessage.from(request("live_call")));
            assertRememberedText(second.messages(), "Prefer trains");

            MemoryClient.await(fixture.conversation.addMessage(
                NewMessage.assistant("Remote update")), fixture.configuration.getWaitTimeout());
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(),
                "content", "Prefers scenic rail routes."));
            var third = provider.get(id.toString());
            assertNotSame(second, third);
            assertEquals(second.messages(), third.messages());
            assertRememberedText(third.messages(), "Remote update", "Prefer trains");
            assertTrue(assertInstanceOf(SystemMessage.class, third.messages().getFirst())
                .text().contains("Prefers scenic rail routes."));
            assertNoToolMessages(third.messages());
            assertEquals(1, fixture.nams.creates);
            assertEquals(1, fixture.nams.stepWrites);
            assertEquals(0, fixture.nams.deletes);
        }
    }

    @Test
    void providerPreservesValidationAndPropagatesNativeLookupFailure() throws Exception {
        try (var fixture = new Fixture()) {
            var provider = new NamsChatMemoryProvider(fixture.client, fixture.configuration);
            for (String invalid : new String[] {null, "", "1-1-1-1-1", "bad", " " + FakeNamsServer.ID}) {
                var error = assertThrows(HttpStatusException.class, () -> provider.get(invalid));
                assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
                assertEquals("conversationId must be a UUID", error.getMessage());
            }
            assertEquals(404, assertThrows(MemoryServiceException.class,
                () -> provider.get(UUID.randomUUID())).statusCode());
            provider.get(FakeNamsServer.ID);
            fixture.nams.status = 503;
            assertEquals(503, assertThrows(MemoryServiceException.class,
                () -> provider.get(FakeNamsServer.ID)).statusCode());
            assertEquals(0, fixture.nams.deletes);
        }
    }

    @Test
    void factoryValidatesConnectionSettings() throws Exception {
        try (var fixture = new Fixture()) {
            var config = fixture.configuration;
            var factory = new NamsClientFactory();
            assertEquals(Duration.ofSeconds(30), new NamsMemoryConfiguration().getWaitTimeout());
            assertThrows(IllegalArgumentException.class, () -> factory.memoryClient(config));
            config.setBaseUrl(fixture.nams.url());
            assertThrows(IllegalArgumentException.class, () -> factory.memoryClient(config));
            config.setApiKey("test-key");
            config.setWaitTimeout(Duration.ZERO);
            assertThrows(IllegalArgumentException.class, () -> factory.memoryClient(config));
            config.setWaitTimeout(Duration.ofSeconds(1));
            assertNotNull(factory.memoryClient(config));
        }
    }

    @Test
    void nativeContextTimeoutClearsTheSameObjectAfterEndpointRecovery() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.configuration.setWaitTimeout(Duration.ofMillis(250));
            var memory = new NamsChatMemory(fixture.conversation, fixture.configuration);
            memory.add(UserMessage.from("Save Zermatt"));
            memory.add(AiMessage.from(request("call_1")));
            memory.add(ToolExecutionResultMessage.from(request("call_1"), "Saved"));
            assertTrue(memory.messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
            fixture.nams.contextDelay = 1000;
            var failure = assertThrows(MemoryClientException.class, memory::messages);
            assertInstanceOf(TimeoutException.class, failure.getCause());
            fixture.nams.contextDelay = 0;
            assertNoToolMessages(memory.messages());
            assertRememberedText(memory.messages(), "Save Zermatt");
            assertEquals(1, fixture.nams.toolWrites);
            assertEquals(0, fixture.nams.deletes);
        }
    }

    @Test
    void nativeInterruptAndTransportFailuresPropagateAndCleanup() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.liveToolTurn();
            Thread.currentThread().interrupt();
            try {
                assertInstanceOf(InterruptedException.class,
                    assertThrows(MemoryClientException.class, fixture.memory::messages).getCause());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertNoToolMessages(fixture.memory.messages());
            fixture.nams.close();
            assertThrows(MemoryClientException.class, fixture.memory::messages);
        }
    }

    @Test
    void partialResultFailurePropagatesNativeExceptionAndDiscardsAllCorrelations() throws Exception {
        assertPartialResultFailure(false);
    }

    @Test
    void committedResultFailurePropagatesNativeExceptionAndDiscardsAllCorrelations() throws Exception {
        assertPartialResultFailure(true);
    }

    private static void assertPartialResultFailure(boolean committed) throws Exception {
        try (var fixture = new Fixture()) {
            var first = request("first");
            var second = request("second");
            fixture.memory.add(UserMessage.from("Save both"));
            fixture.memory.add(AiMessage.from(List.of(first, second)));
            fixture.memory.add(ToolExecutionResultMessage.from(first, "first output"));
            if (committed) fixture.nams.commitThenFailToolWriteNumber = 2;
            else fixture.nams.failToolWriteNumber = 2;
            var failure = assertThrows(MemoryServiceException.class,
                () -> fixture.memory.add(ToolExecutionResultMessage.from(second, "second output")));
            assertEquals(503, failure.statusCode());
            assertEquals(2, fixture.nams.toolWrites);
            assertEquals(committed ? 2 : 1, fixture.nams.toolCalls.size());
            assertRememberedText(fixture.memory.messages(), "Save both");
            assertThrows(IllegalArgumentException.class,
                () -> fixture.memory.add(ToolExecutionResultMessage.from(second, "late")));
            assertEquals(2, fixture.nams.toolWrites);
            fixture.memory.add(UserMessage.from("Continue"));
            assertNoToolMessages(fixture.memory.messages());
            assertEquals(1, fixture.nams.stepWrites);
        }
    }

    @Test
    void remoteDeletionRejectsExistingAdaptersAndFreshLookupsWithoutRecreation() throws Exception {
        try (var fixture = new Fixture()) {
            var provider = new NamsChatMemoryProvider(fixture.client, fixture.configuration);
            var memory = provider.get(FakeNamsServer.ID);
            memory.add(UserMessage.from("Question"));
            MemoryClient.await(fixture.conversation.delete(), fixture.configuration.getWaitTimeout());
            assertEquals(404, assertThrows(MemoryServiceException.class,
                () -> provider.get(FakeNamsServer.ID)).statusCode());
            assertEquals(404, assertThrows(MemoryServiceException.class, memory::messages).statusCode());
            assertEquals(404, assertThrows(MemoryServiceException.class,
                () -> memory.add(UserMessage.from("Still there?"))).statusCode());
            assertEquals(1, fixture.nams.creates);
            assertEquals(1, fixture.nams.deletes);
        }
    }

    @Test
    void seededEmptyConversationHasIndependentContextAndDeletion() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.liveToolTurn();
            fixture.nams.observations.add(Map.of("id", UUID.randomUUID().toString(), "content", "Main only"));
            var id = UUID.randomUUID();
            fixture.nams.seedEmptyConversation(id.toString());
            var empty = MemoryClient.await(fixture.client.getConversation(id), fixture.configuration.getWaitTimeout());
            assertTrue(MemoryClient.await(empty.context(), fixture.configuration.getWaitTimeout()).observations().isEmpty());
            MemoryClient.await(empty.delete(), fixture.configuration.getWaitTimeout());
            assertEquals(404, assertThrows(MemoryServiceException.class,
                () -> MemoryClient.await(empty.context(), fixture.configuration.getWaitTimeout())).statusCode());
            assertEquals(1, fixture.nams.steps.size());
            assertEquals(1, fixture.nams.toolCalls.size());
            assertEquals(1, fixture.nams.observations.size());
        }
    }

    private static ToolExecutionRequest request(String id) {
        return ToolExecutionRequest.builder().id(id).name("addToWishlist").arguments("{\"itemId\":1}").build();
    }

    private static final class Fixture implements AutoCloseable {
        final FakeNamsServer nams = new FakeNamsServer();
        final NamsMemoryConfiguration configuration = new NamsMemoryConfiguration();
        final MemoryClient client = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
        final Conversation conversation;
        final NamsChatMemory memory;

        Fixture() throws Exception {
            configuration.setWaitTimeout(Duration.ofSeconds(1));
            conversation = MemoryClient.await(client.createConversation(new CreateConversation(null, Map.of("title", "Save Zermatt"))), configuration.getWaitTimeout());
            memory = new NamsChatMemory(conversation, configuration);
        }

        void liveToolTurn() {
            memory.add(UserMessage.from("Save Zermatt"));
            memory.add(AiMessage.from(request("call_1")));
            memory.add(ToolExecutionResultMessage.from(request("call_1"), "Saved"));
        }

        @Override
        public void close() {
            nams.close();
            assertEquals(0, nams.traceReads);
        }
    }

    private static void assertNoToolMessages(List<ChatMessage> messages) {
        assertTrue(messages.stream().noneMatch(ToolExecutionResultMessage.class::isInstance));
        assertTrue(messages.stream().noneMatch(message ->
            message instanceof AiMessage ai && ai.hasToolExecutionRequests()));
    }

    private static void assertRememberedText(List<ChatMessage> messages, String... expected) {
        assertEquals(1, messages.size());
        String prompt = assertInstanceOf(SystemMessage.class, messages.getFirst()).text();
        String marker = "NAMS_CONTEXT_JSON:\n";
        assertTrue(prompt.contains(marker));
        var context = JsonMapper.builder().build().readTree(
            prompt.substring(prompt.indexOf(marker) + marker.length()));
        var contents = new ArrayList<String>();
        context.get("messages").forEach(message -> contents.add(message.get("content").asText()));
        assertEquals(List.of(expected), contents);
    }
}
