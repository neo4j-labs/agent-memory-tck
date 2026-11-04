package com.example.memory;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.ConversationContext;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.conversation.Reflection;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamsContextFormatterTest {
    @Test void returnsAllTiersAsTypedChronologicalMessages() {
        var recent = IntStream.range(0, 25).mapToObj(i -> new org.neo4j.agentmemory.conversation.Message(UUID.randomUUID(), i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, "recent-" + i)).toList().reversed();
        var before = List.copyOf(recent);
        var context = new ConversationContext(List.of(new Reflection(UUID.randomUUID(), "remember {Zurich}")), List.of(), recent);
        var recalled = new NamsContextFormatter().format(context);
        assertEquals(26, recalled.size());
        assertInstanceOf(SystemMessage.class, recalled.getFirst());
        assertTrue(recalled.getFirst().getText().contains("remember {Zurich}"));
        assertFalse(recalled.getFirst().getText().contains("recent-"));
        assertEquals("recent-0", recalled.get(1).getText());
        assertEquals("recent-24", recalled.getLast().getText());
        assertEquals(IntStream.range(0, 25).mapToObj(i -> i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT).toList(), recalled.subList(1, recalled.size()).stream().map(Message::getMessageType).toList());
        assertEquals(before, context.recentMessages());
    }
    @Test void preservesRepeatedTextAndNativeSystemRole() {
        var context = new ConversationContext(List.of(), List.of(), List.of(new org.neo4j.agentmemory.conversation.Message(UUID.randomUUID(), MessageRole.SYSTEM, "same"), new org.neo4j.agentmemory.conversation.Message(UUID.randomUUID(), MessageRole.USER, "same")));
        var recalled = new NamsContextFormatter().format(context);
        assertEquals(List.of("same", "same"), recalled.subList(1, 3).stream().map(Message::getText).toList());
        assertEquals(MessageType.SYSTEM, recalled.getLast().getMessageType());
    }
    @Test void emptyContextKeepsLabelledTiers() {
        var recalled = new NamsContextFormatter().format(new ConversationContext(List.of(), List.of(), List.of()));
        assertEquals(1, recalled.size());
        assertTrue(recalled.getFirst().getText().contains("Reflections:\nObservations:"));
    }
}
