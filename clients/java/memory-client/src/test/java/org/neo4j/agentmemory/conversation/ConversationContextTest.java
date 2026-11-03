package org.neo4j.agentmemory.conversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationContextTest {
    @Test
    void normalizesNullCollectionsToEmptyLists() {
        var context = new ConversationContext(null, null, null);

        assertThat(context.reflections()).isEmpty();
        assertThat(context.observations()).isEmpty();
        assertThat(context.recentMessages()).isEmpty();
    }

    @Test
    void defensivelyCopiesAndFreezesCollections() {
        var source = new ArrayList<>(List.of(new Reflection(UUID.randomUUID(), "remember")));
        var context = new ConversationContext(source, List.of(), List.of());

        source.clear();

        assertThat(context.reflections()).hasSize(1);
        assertThatThrownBy(() -> context.reflections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
