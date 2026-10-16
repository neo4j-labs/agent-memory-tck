package org.neo4j.agentmemory.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ListConversationsTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void blankUserIdIsAbsent(String userId) {
        var request = new ListConversations(25, userId);
        assertThat(request.userId()).isNull();
        assertThat(request.getUserId()).isEmpty();
    }

    @Test
    void userIdIsTrimmed() {
        var request = new ListConversations(25, " alice ");
        assertThat(request.userId()).isEqualTo("alice");
        assertThat(request.getUserId()).contains("alice");
    }

    @Test
    void limitOnlyConstructorHasNoUser() {
        var request = new ListConversations(25);
        assertThat(request.limit()).isEqualTo(25);
        assertThat(request.getUserId()).isEmpty();
    }
}
