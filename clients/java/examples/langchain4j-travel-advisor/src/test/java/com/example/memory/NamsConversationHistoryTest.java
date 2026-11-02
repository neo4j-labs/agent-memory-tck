package com.example.memory;

import io.micronaut.http.exceptions.HttpStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.NewMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamsConversationHistoryTest {
    @Test
    void historyShowsActualDialogueWithoutDerivedContext() throws Exception {
        try (var nams = new FakeNamsServer()) {
            var configuration = new NamsMemoryConfiguration();
            var client = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
            var history = new NamsConversationHistory(client, configuration);
            String id = history.createConversation("Plan Zermatt");
            var conversation = MemoryClient.await(client.getConversation(UUID.fromString(id)),
                configuration.getWaitTimeout());
            MemoryClient.await(conversation.addMessage(NewMessage.user("Plan Zermatt")),
                configuration.getWaitTimeout());
            MemoryClient.await(conversation.addMessage(NewMessage.assistant("Travel by train.")),
                configuration.getWaitTimeout());
            var detail = history.getConversation(id);
            assertEquals(id, detail.conversationId());
            assertEquals(List.of(
                new NamsConversationHistory.ConversationMessage("user", "Plan Zermatt"),
                new NamsConversationHistory.ConversationMessage("assistant", "Travel by train.")),
                detail.messages());
            assertEquals(2, nams.appends);
        }
    }

    @Test
    void deletedSeededEmptyConversationRejectsMessageReadsAndWrites() throws Exception {
        try (var nams = new FakeNamsServer()) {
            var id = UUID.randomUUID();
            nams.seedEmptyConversation(id.toString());
            var client = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
            var configuration = new NamsMemoryConfiguration();
            var conversation = MemoryClient.await(client.getConversation(id), configuration.getWaitTimeout());
            MemoryClient.await(conversation.delete(), configuration.getWaitTimeout());

            var read = assertThrows(MemoryServiceException.class,
                () -> MemoryClient.await(conversation.messages(1), configuration.getWaitTimeout()));
            assertEquals(404, read.statusCode());
            var write = assertThrows(MemoryServiceException.class,
                () -> MemoryClient.await(conversation.addMessage(NewMessage.user("Still there?")), configuration.getWaitTimeout()));
            assertEquals(404, write.statusCode());
            assertEquals(1, nams.deletes);
            assertEquals(1, nams.appends);
        }
    }

    @Test
    void titlesPreviewsBoundsAndEmptyHistory() throws Exception {
        try (var nams = new FakeNamsServer()) {
            String emptyId = UUID.randomUUID().toString();
            nams.seedEmptyConversation(emptyId);
            var client = MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build());
            var configuration = new NamsMemoryConfiguration();
            var history = new NamsConversationHistory(client, configuration);
            String id = history.createConversation("  Find hotels in Zermatt  ");
            assertEquals(FakeNamsServer.ID, id);
            assertEquals("Find hotels in Zermatt", history.getConversation(id).title());
            var conversation = MemoryClient.await(client.getConversation(UUID.fromString(id)), configuration.getWaitTimeout());
            for (int i = 0; i < 21; i++) MemoryClient.await(conversation.addMessage(NewMessage.user("old " + i)), configuration.getWaitTimeout());
            MemoryClient.await(conversation.addMessage(NewMessage.assistant(" repeated\n latest  text ")), configuration.getWaitTimeout());
            MemoryClient.await(conversation.addMessage(NewMessage.assistant(" repeated\n latest  text ")), configuration.getWaitTimeout());
            var summaries = history.listConversations();
            assertEquals(2, summaries.size());
            assertEquals("repeated latest text", summaries.stream().filter(s -> s.conversationId().equals(id)).findFirst().orElseThrow().preview());
            assertEquals("No messages yet", summaries.stream().filter(s -> s.conversationId().equals(emptyId)).findFirst().orElseThrow().preview());
            var detail = history.getConversation(id);
            assertEquals(20, detail.messages().size());
            assertEquals("old 3", detail.messages().getFirst().content());
            assertEquals(detail.messages().get(18), detail.messages().get(19));
            assertTrue(history.getConversation(emptyId).messages().isEmpty());
            assertThrows(MemoryServiceException.class, () -> history.getConversation(UUID.randomUUID().toString()));
            assertThrows(MemoryServiceException.class, () -> history.requireConversation(UUID.randomUUID().toString()));
        }
    }

    @Test
    void formatsTitlesAndValidatesIdsBeforeRemoteAccess() throws Exception {
        try (var nams = new FakeNamsServer()) {
            var history = new NamsConversationHistory(MemoryClient.create(MemoryClientConfiguration.builder()
            .baseUrl(nams.url()).apiKey("test-key").build()), new NamsMemoryConfiguration());
            history.createConversation(" ");
            assertEquals("New Swiss adventure", history.getConversation(FakeNamsServer.ID).title());
            history.createConversation("x".repeat(81));
            assertEquals("x".repeat(79) + "…", history.getConversation(FakeNamsServer.ID).title());
            for (String invalid : new String[] {null, "", "1-1-1-1-1", "invalid", " " + FakeNamsServer.ID}) {
                var error = assertThrows(HttpStatusException.class, () -> history.requireConversation(invalid));
                assertEquals(400, error.getStatus().getCode());
                assertEquals("conversationId must be a UUID", error.getMessage());
                var detailError = assertThrows(HttpStatusException.class, () -> history.getConversation(invalid));
                assertEquals(error.getStatus(), detailError.getStatus());
                assertEquals(error.getMessage(), detailError.getMessage());
            }
            assertEquals(2, nams.creates);
            history.requireConversation(FakeNamsServer.ID.toUpperCase());
            assertEquals(FakeNamsServer.ID, history.getConversation(FakeNamsServer.ID.toUpperCase()).conversationId());
        }
    }

    @Test
    void sortsMissingTimesLastAndProjectsOnlyVisibleRolesWithoutMutatingHistory() {
        var client = mock(MemoryClient.class);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var third = UUID.randomUUID();
        var fourth = UUID.randomUUID();
        var conversations = List.of(
            conversation(first, Map.of(), null, null),
            conversation(second, Map.of(), Instant.EPOCH, Instant.EPOCH),
            conversation(third, Map.of(), Instant.EPOCH.plusSeconds(1), Instant.EPOCH),
            conversation(fourth, Map.of(), null, Instant.EPOCH.plusSeconds(1)));
        when(client.listConversations(any())).thenReturn(CompletableFuture.completedFuture(conversations));
        for (var conversation : conversations) {
            when(conversation.messages(1)).thenReturn(CompletableFuture.completedFuture(List.of(
                message(MessageRole.ASSISTANT, "x".repeat(97)))));
        }
        var history = new NamsConversationHistory(client, new NamsMemoryConfiguration());
        var summaries = history.listConversations();
        verify(client).listConversations(new ListConversations(50));
        assertEquals(List.of(fourth, third, second, first).stream().map(UUID::toString).toList(),
            summaries.stream().map(NamsConversationHistory.ConversationSummary::conversationId).toList());
        assertEquals("x".repeat(95) + "…", summaries.getFirst().preview());
        assertNull(summaries.getLast().createdAt());
        when(client.getConversation(first)).thenReturn(CompletableFuture.completedFuture(conversations.getFirst()));
        var newestFirst = List.of(message(MessageRole.ASSISTANT, "reply"),
            message(MessageRole.SYSTEM, "hidden"), message(MessageRole.USER, "question"));
        when(conversations.getFirst().messages(20)).thenReturn(CompletableFuture.completedFuture(newestFirst));
        var detail = history.getConversation(first.toString());
        assertEquals("New Swiss adventure", detail.title());
        assertEquals(List.of(new NamsConversationHistory.ConversationMessage("user", "question"),
            new NamsConversationHistory.ConversationMessage("assistant", "reply")), detail.messages());
        assertEquals("reply", newestFirst.getFirst().content());
    }

    @Test
    void ordersIdenticalTimestampsByDescendingConversationId() {
        var client = mock(MemoryClient.class);
        var earlierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var laterId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        var earlier = conversation(earlierId, Map.of(), Instant.EPOCH, Instant.EPOCH);
        var later = conversation(laterId, Map.of(), Instant.EPOCH, Instant.EPOCH);
        when(client.listConversations(new ListConversations(50))).thenReturn(
            CompletableFuture.completedFuture(List.of(earlier, later)));
        when(earlier.messages(1)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(later.messages(1)).thenReturn(CompletableFuture.completedFuture(List.of()));
        var summaries = new NamsConversationHistory(client, new NamsMemoryConfiguration()).listConversations();
        assertEquals(List.of(laterId.toString(), earlierId.toString()),
            summaries.stream().map(NamsConversationHistory.ConversationSummary::conversationId).toList());
    }

    private static Message message(MessageRole role, String content) {
        return new Message(UUID.randomUUID(), role, content);
    }

    private static Conversation conversation(UUID id, Map<String, String> metadata,
                                             Instant createdAt, Instant updatedAt) {
        var conversation = mock(Conversation.class);
        when(conversation.id()).thenReturn(id);
        when(conversation.metadata()).thenReturn(metadata);
        when(conversation.createdAt()).thenReturn(Optional.ofNullable(createdAt));
        when(conversation.updatedAt()).thenReturn(Optional.ofNullable(updatedAt));
        return conversation;
    }
}
