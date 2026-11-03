package org.neo4j.agentmemory.conversation;

import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationIdentityTest {
    private static final UUID ID =
            UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");

    @Test
    void representsKnownAndUnknownConversationUsersExplicitly() {
        assertThat(CreateConversation.class.isRecord()).isTrue();
        var absent = new CreateConversation();
        var explicitNull = new CreateConversation((String) null);
        var blank = new CreateConversation(" \t");
        var known = new CreateConversation(" alice ");

        assertThat(absent.userId()).isNull();
        assertThat(explicitNull.userId()).isNull();
        assertThat(blank.userId()).isNull();
        assertThat(known.userId()).isEqualTo("alice");
        assertThat(absent.getUserId()).isEmpty();
        assertThat(known.getUserId()).contains("alice");
    }

    @Test
    void conversationsCompareByUuid() {
        var client = client();
        var first = new Conversation(client, ID, "alice");
        var second = new Conversation(client, ID, "bob");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.userId()).isEqualTo(Optional.of("alice"));
    }

    @Test
    void identifiedSnapshotValuesCompareByUuid() {
        var message = new Message(ID, MessageRole.USER, "first");
        var changedMessage = new Message(ID, MessageRole.ASSISTANT, "second");
        var observation = new Observation(ID, "first");
        var changedObservation = new Observation(ID, "second");
        var reflection = new Reflection(ID, "first");
        var changedReflection = new Reflection(ID, "second");

        assertThat(message).isEqualTo(changedMessage);
        assertThat(observation).isEqualTo(changedObservation);
        assertThat(reflection).isEqualTo(changedReflection);
    }

    private static MemoryClient client() {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl("https://memory.test/v1").apiKey("key").build());
    }
}
