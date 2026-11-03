package org.neo4j.agentmemory.conformance;

import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.CreateConversation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SessionsTest {
    private final FakeMemoryClient client = new FakeMemoryClient();
    private final Sessions sessions = new Sessions(client);

    @Test
    void firstMessageBindsANewConversationForTheTckUser() {
        var conversation = sessions.bind("first");
        assertThat(client.creates).containsExactly(
                new CreateConversation("tck-java", Map.of("tck.session", "first")));
        assertThat(sessions.bind("first")).isSameAs(conversation);
        assertThat(client.lists).hasSize(1);
        assertThat(client.lists.get(0).limit()).isEqualTo(200);
        assertThat(client.lists.get(0).userId()).isEqualTo("tck-java");
    }

    @Test
    void readingAnUnboundSessionCreatesNothing() {
        assertThat(sessions.find("unknown")).isEmpty();
        sessions.clear("unknown");
        assertThat(client.creates).isEmpty();
    }

    @Test
    void clearingASessionLetsItsNextMessageBindANewConversation() {
        var old = sessions.bind("first");
        sessions.clear("first");
        var next = sessions.bind("first");
        assertThat(next.id()).isNotEqualTo(old.id());
        assertThat(client.conversations).containsExactly(next);
    }

    @Test
    void sessionsAreRecoveredFromConversationMetadataAfterRestart() {
        var original = sessions.bind("first");
        client.creates.clear();
        assertThat(new Sessions(client).bind("first")).isSameAs(original);
        assertThat(client.creates).isEmpty();
    }

    @Test
    void listingReturnsSessionNamesAndIgnoresUntaggedConversations() {
        var tagged = sessions.bind("first");
        client.createConversation(new CreateConversation("tck-java"));
        var other = client.createConversation(
                new CreateConversation("other", Map.of("tck.session", "foreign"))).join();
        assertThat(sessions.all(100)).containsExactly(tagged);
        assertThat(sessions.all(100)).extracting(c -> c.metadata().get("tck.session"))
                .containsExactly("first");
        assertThat(client.lists.get(client.lists.size() - 1).limit()).isEqualTo(100);
        sessions.forgetAll();
        assertThat(client.conversations).containsExactly(other);
        assertThat(sessions.find("first")).isEmpty();
    }

    @Test
    void forgettingAllRepeatsUntilCompleteAndGivesUp() {
        sessions.bind("first");
        client.deletions.addAll(List.of(false, false, true));
        sessions.forgetAll();
        assertThat(client.deletedUsers).containsExactly("tck-java", "tck-java", "tck-java");
        assertThat(sessions.find("first")).isEmpty();

        client.deletedUsers.clear();
        for (int i = 0; i < 10; i++) {
            client.deletions.add(false);
        }
        assertThatIllegalStateException().isThrownBy(sessions::forgetAll)
                .withMessage("clear_all_data still incomplete after 10 calls");
        assertThat(client.deletedUsers).hasSize(10).containsOnly("tck-java");
    }
}
