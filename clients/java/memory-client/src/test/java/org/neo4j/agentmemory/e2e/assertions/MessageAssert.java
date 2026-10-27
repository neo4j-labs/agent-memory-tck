package org.neo4j.agentmemory.e2e.assertions;

import org.assertj.core.api.AbstractAssert;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;

import java.util.Objects;

public final class MessageAssert extends AbstractAssert<MessageAssert, Message> {
    private MessageAssert(Message actual) {
        super(actual, MessageAssert.class);
    }

    public static MessageAssert assertThat(Message actual) {
        return new MessageAssert(actual);
    }

    public MessageAssert hasId() {
        isNotNull();
        if (actual.id() == null) failWithMessage("Expected message to have an id");
        return this;
    }

    public MessageAssert hasRole(MessageRole expected) {
        isNotNull();
        if (actual.role() != expected)
            failWithMessage("Expected message role <%s> but was <%s>", expected, actual.role());
        return this;
    }

    public MessageAssert hasContent(String expected) {
        isNotNull();
        if (!Objects.equals(actual.content(), expected))
            failWithMessage("Expected message content <%s> but was <%s>", expected, actual.content());
        return this;
    }
}
