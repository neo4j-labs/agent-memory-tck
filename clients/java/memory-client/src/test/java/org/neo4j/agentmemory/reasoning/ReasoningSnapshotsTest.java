package org.neo4j.agentmemory.reasoning;

import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReasoningSnapshotsTest {
    private static final UUID CONVERSATION_ID =
            UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final UUID STEP_ID =
            UUID.fromString("673139d8-dd48-48eb-a5a9-cc84e6e938e3");
    private static final UUID CALL_ID =
            UUID.fromString("15c63f73-f00f-45de-b62e-851ea483a552");

    @Test
    void traceCopiesCollectionsAndGroupsToolCallsByStep() {
        var step = step();
        var call = new ToolCall(
                CALL_ID, STEP_ID, "search", "{}", "[]",
                ToolCallStatus.SUCCESS, null, null);
        var source = new ArrayList<>(List.of(step));
        var trace = new ReasoningTrace(CONVERSATION_ID, source, List.of(call));

        source.clear();

        assertThat(step.result()).isEmpty();
        assertThat(step.createdAt()).isEmpty();
        assertThat(trace.steps()).containsExactly(step);
        assertThat(trace.toolCalls(step)).containsExactly(call);
        assertThatThrownBy(() -> trace.toolCalls().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explanationsCopyToolCallsAndInfluencedEntities() {
        var explanation = new ReasoningStepExplanation(
                step(),
                List.of(),
                List.of(new Entity(
                        UUID.randomUUID(), "Alice", "person", (String) null)));

        assertThat(explanation.step().conversationId()).isEqualTo(CONVERSATION_ID);
        assertThatThrownBy(() -> explanation.influencedEntities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ReasoningStep step() {
        return new ReasoningStep(
                client(), STEP_ID, CONVERSATION_ID,
                "reason", "act", null, null);
    }

    private static MemoryClient client() {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl("https://memory.test/v1").apiKey("key").build());
    }
}
