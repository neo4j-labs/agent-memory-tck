package org.neo4j.agentmemory.e2e.assertions;

import org.assertj.core.api.AbstractAssert;
import org.neo4j.agentmemory.conversation.ConversationContext;
import org.neo4j.agentmemory.conversation.Message;

import java.util.UUID;

public final class ConversationContextAssert extends AbstractAssert<ConversationContextAssert, ConversationContext> {
    private ConversationContextAssert(ConversationContext actual) {
        super(actual, ConversationContextAssert.class);
    }

    public static ConversationContextAssert assertThat(ConversationContext actual) {
        return new ConversationContextAssert(actual);
    }

    public ConversationContextAssert hasThreeTiers() {
        isNotNull();
        if (actual.reflections() == null) failWithMessage("Expected conversation context to have a reflections list");
        if (actual.observations() == null)
            failWithMessage("Expected conversation context to have an observations list");
        if (actual.recentMessages() == null)
            failWithMessage("Expected conversation context to have a recent messages list");
        return this;
    }

    public ConversationContextAssert containsRecentMessages(UUID... expectedIds) {
        isNotNull();
        if (actual.recentMessages() == null)
            failWithMessage("Expected conversation context to have a recent messages list");
        var actualIds = actual.recentMessages().stream().map(Message::id).toList();
        for (var expectedId : expectedIds) {
            if (!actualIds.contains(expectedId))
                failWithMessage("Expected recent messages to contain id <%s> but ids were <%s>", expectedId, actualIds);
        }
        return this;
    }
}
