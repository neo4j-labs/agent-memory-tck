package com.example.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;

class NamsConversationHistoryTest {
    private final MemoryServiceStub stub = new MemoryServiceStub();
    private final NamsConversationHistory history = new NamsConversationHistory(stub.client(), Duration.ofSeconds(5));
    @AfterEach void close() { stub.close(); }

    @Test void createsNativeIdentityAndInitialTitle() {
        var created = history.create("  " + "x".repeat(100) + "  ");
        assertEquals("x".repeat(79) + "…", created.metadata().get("title"));
        assertEquals(created.id(), history.require(created.id()).id());
        assertFalse(stub.requests().getFirst().body().has("id"));
        int count = stub.requests().size();
        assertThrows(IllegalArgumentException.class, () -> history.create(" \n "));
        assertEquals(count, stub.requests().size());
    }
    @Test void listsNativeOrderAndFirstSnippetWithoutChildReads() {
        var b = stub.seed("B", List.of(NewMessage.user("first user text"), NewMessage.assistant("latest text")));
        var a = stub.seed("A", List.of());
        stub.setListOrder(List.of(b.id(), a.id()));
        Instant remoteCreatedAt = b.createdAt().orElseThrow();
        Instant remoteUpdatedAt = b.updatedAt().orElseThrow();
        assertTrue(a.createdAt().orElseThrow().isAfter(remoteCreatedAt));
        int before = stub.requests().size();
        var summaries = history.list();
        var requestsDuringList = stub.requests().subList(before, stub.requests().size());
        var listRequest = requestsDuringList.getFirst();
        assertEquals(List.of(b.id().toString(), a.id().toString()), summaries.stream().map(NamsConversationHistory.ConversationSummary::conversationId).toList());
        assertEquals("first user text", summaries.getFirst().preview());
        assertEquals("limit=50", listRequest.query());
        assertEquals(1, requestsDuringList.size());
        assertEquals(remoteCreatedAt, summaries.getFirst().createdAt());
        assertEquals(remoteUpdatedAt, summaries.getFirst().updatedAt());
    }
    @Test void projectsTwentyOfSixtyWithoutDeletingOrDeduplicating() {
        var conversation = stub.seed("Turns", IntStream.range(0,60).mapToObj(i -> NewMessage.user("Turn " + i)).toList());
        var detail = history.detail(conversation.id());
        assertEquals(20, detail.messages().size());
        assertEquals("Turn 40", detail.messages().getFirst().content());
        assertEquals("Turn 59", detail.messages().getLast().content());
        assertEquals(60, stub.messages(conversation.id()).size());
        var messagesRequest = stub.requests().getLast();
        assertEquals("limit=20", messagesRequest.query());
        var repeatedConversation = stub.seed("Repeated", List.of(NewMessage.user("same"), NewMessage.user("same")));
        var repeated = history.detail(repeatedConversation.id());
        assertEquals(List.of("same", "same"), repeated.messages().stream().map(NamsConversationHistory.ConversationMessage::content).toList());
        assertEquals(60, new NamsConversationHistory(stub.client(), Duration.ofSeconds(5)).require(conversation.id()).messageCount().orElseThrow());
    }
    @Test void preservesDisplayRules() {
        var longConversation = stub.seed("t".repeat(100), List.of(NewMessage.user("  " + "p".repeat(110) + "\n\t ")));
        var emptyConversation = stub.seed(null, List.of());
        stub.setListOrder(List.of(longConversation.id(), emptyConversation.id()));
        var summaries = history.list();
        var longPreview = summaries.getFirst();
        assertEquals("p".repeat(95) + "…", longPreview.preview());
        var empty = summaries.getLast();
        assertEquals("New Swiss adventure", empty.title());
        assertEquals("No messages yet", empty.preview());
        assertEquals("New Swiss adventure", history.detail(emptyConversation.id()).title());
        var conversation = stub.seed("visible", List.of(new NewMessage(MessageRole.SYSTEM,"hidden"), NewMessage.user("u"), NewMessage.assistant("a"), NewMessage.assistant("  ")));
        var visible = history.detail(conversation.id());
        assertEquals(List.of("user", "assistant"), visible.messages().stream().map(NamsConversationHistory.ConversationMessage::role).toList());
        var whitespace = stub.seed("Whitespace", List.of(NewMessage.user(" first\n user\t text ")));
        stub.setListOrder(List.of(whitespace.id()));
        assertEquals("first user text", history.list().getFirst().preview());
    }
    @Test void failsInsteadOfInventingHistory() {
        var deletedId = stub.seed("deleted", List.of()).id(); stub.delete(deletedId);
        assertEquals(404, assertThrows(MemoryServiceException.class, () -> history.require(deletedId)).statusCode());
        assertEquals(404, assertThrows(MemoryServiceException.class, () -> history.detail(deletedId)).statusCode());
    }
    @Test void rejectsMalformedListEnvelope() {
        stub.failNext("GET", "/v1/conversations", 200, "{}");
        assertThrows(MemoryClientException.class, history::list);
    }
    @Test void rejectsMalformedMessagesEnvelope() {
        var conversation = stub.seed("bad messages", List.of());
        stub.failNext("GET", "/v1/conversations/" + conversation.id() + "/messages", 200, "{}");
        assertThrows(MemoryClientException.class, () -> history.detail(conversation.id()));
    }
    @Test void rejectsMissingCreatedAt() { missingTimestamp("updatedAt"); }
    @Test void rejectsMissingUpdatedAt() { missingTimestamp("createdAt"); }
    private void missingTimestamp(String retained) {
        stub.failNext("GET", "/v1/conversations", 200, "{\"conversations\":[{\"id\":\"" + UUID.randomUUID() + "\",\"" + retained + "\":\"2026-01-01T00:00:00Z\"}]}");
        assertThrows(MemoryClientException.class, history::list);
    }
}
