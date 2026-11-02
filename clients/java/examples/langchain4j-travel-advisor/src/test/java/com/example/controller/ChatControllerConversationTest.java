package com.example.controller;

import com.example.memory.FakeNamsServer;
import com.example.memory.NamsConversationHistory;
import com.example.service.TravelAssistant;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatControllerConversationTest {
    private final NamsConversationHistory memory = mock(NamsConversationHistory.class);
    private final TravelAssistant assistant = mock(TravelAssistant.class);
    private final ChatController controller = new ChatController(assistant, memory);
    @Test void bothRoutesUseServerIdentityForAssistantAndReply() {
        when(memory.createConversation("hello")).thenReturn(FakeNamsServer.ID);
        when(assistant.chat(FakeNamsServer.ID, "hello")).thenReturn("reply");
        assertEquals(new ChatController.ChatReply(FakeNamsServer.ID, "reply"), controller.chat(new ChatController.ChatRequest("hello", null)));
        assertEquals(new ChatController.ChatReply(FakeNamsServer.ID, "reply"), controller.chatGet("hello", " "));
        verify(assistant, times(2)).evictChatMemory(FakeNamsServer.ID);
        verify(assistant, times(2)).chat(FakeNamsServer.ID, "hello");
    }
    @Test void validatesBeforeCreationAndRejectsUnknownIdentities() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpStatusException.class, () -> controller.chatGet(" ", null)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpStatusException.class, () -> controller.chatGet("hello", "bad")).getStatus());
        verifyNoInteractions(memory);
        doThrow(new HttpStatusException(HttpStatus.NOT_FOUND, "missing")).when(memory).requireConversation(FakeNamsServer.ID);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> controller.chatGet("hello", FakeNamsServer.ID)).getStatus());
        verify(memory, never()).createConversation(any()); verifyNoInteractions(assistant);
    }
    @Test void rejectsNoncanonicalIdsWithTheSamePublicErrorBeforeRemoteAccess() {
        for (String invalid : new String[] {"1-1-1-1-1", "bad", " " + FakeNamsServer.ID}) {
            var getError = assertThrows(HttpStatusException.class, () -> controller.chatGet("hello", invalid));
            var postError = assertThrows(HttpStatusException.class,
                () -> controller.chat(new ChatController.ChatRequest("hello", invalid)));
            assertEquals(HttpStatus.BAD_REQUEST, getError.getStatus());
            assertEquals("conversationId must be a UUID", getError.getMessage());
            assertEquals(getError.getStatus(), postError.getStatus());
            assertEquals(getError.getMessage(), postError.getMessage());
        }
        for (String invalid : new String[] {null, "", " ", "1-1-1-1-1", "bad", " " + FakeNamsServer.ID}) {
            var error = assertThrows(HttpStatusException.class, () -> controller.conversation(invalid));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
            assertEquals("conversationId must be a UUID", error.getMessage());
        }
        verifyNoInteractions(memory, assistant);
    }
    @Test void preservesConversationJsonFields() throws Exception {
        when(memory.listConversations()).thenReturn(java.util.List.of(new NamsConversationHistory.ConversationSummary(
            FakeNamsServer.ID, "Plan Zermatt", "Try the railway.", null, null)));
        when(memory.getConversation(FakeNamsServer.ID)).thenReturn(new NamsConversationHistory.ConversationDetail(
            FakeNamsServer.ID, "Plan Zermatt", java.util.List.of(new NamsConversationHistory.ConversationMessage("user", "Plan Zermatt"))));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var list = mapper.readTree(mapper.writeValueAsString(controller.conversations())).get(0);
        for (String field : java.util.List.of("conversationId", "title", "preview", "createdAt", "updatedAt")) assertTrue(list.has(field));
        var detail = mapper.readTree(mapper.writeValueAsString(controller.conversation(FakeNamsServer.ID)));
        assertEquals(FakeNamsServer.ID, detail.get("conversationId").asText());
        assertEquals("user", detail.get("messages").get(0).get("role").asText());
        assertEquals("Plan Zermatt", detail.get("messages").get(0).get("content").asText());
    }
    @Test
    void failureEvictsTheCanonicalFrameworkKeyAndPreservesTheException() {
        var failure = new IllegalStateException("failed");
        when(assistant.chat(FakeNamsServer.ID, "hello")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> controller.chatGet("hello", FakeNamsServer.ID.toUpperCase())));
        verify(memory).requireConversation(FakeNamsServer.ID);
        var order = org.mockito.Mockito.inOrder(assistant);
        order.verify(assistant).chat(FakeNamsServer.ID, "hello");
        order.verify(assistant).evictChatMemory(FakeNamsServer.ID);
    }

    @Test
    void successfulReturnWithoutMemoryCallbacksStillEvictsTheFrameworkKey() {
        when(assistant.chat(FakeNamsServer.ID, "hello")).thenReturn("reply");
        assertEquals(new ChatController.ChatReply(FakeNamsServer.ID, "reply"),
            controller.chatGet("hello", FakeNamsServer.ID));
        var order = org.mockito.Mockito.inOrder(assistant);
        order.verify(assistant).chat(FakeNamsServer.ID, "hello");
        order.verify(assistant).evictChatMemory(FakeNamsServer.ID);
    }
}
