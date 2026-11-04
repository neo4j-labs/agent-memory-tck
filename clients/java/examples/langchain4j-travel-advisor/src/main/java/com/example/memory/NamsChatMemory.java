package com.example.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ReasoningStep;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import static org.neo4j.agentmemory.MemoryClient.await;

/** Reads native conversation context and retains tool exchanges only for the active turn. */
public final class NamsChatMemory implements ChatMemory {
    private final Conversation conversation;
    private final Duration waitTimeout;
    private SystemMessage currentPrompt;
    private final List<ChatMessage> turnMessages = new ArrayList<>();
    private final Set<UUID> turnTextIds = new HashSet<>();
    private final Map<String, RecordedToolRequest> toolRequests = new HashMap<>();

    private record RecordedToolRequest(ReasoningStep step, ToolExecutionRequest request) {}

    public NamsChatMemory(Conversation conversation, NamsMemoryConfiguration configuration) {
        this.conversation = conversation;
        this.waitTimeout = configuration.getWaitTimeout();
    }

    @Override
    public Object id() {
        return conversation.id();
    }

    @Override
    public List<ChatMessage> messages() {
        try {
            var context = await(conversation.context(), waitTimeout);
            return NamsChatContext.assemble(context, currentPrompt,
                Set.copyOf(turnTextIds), List.copyOf(turnMessages));
        } catch (RuntimeException | Error failure) {
            endTurn();
            throw failure;
        }
    }

    @Override
    public void add(ChatMessage message) {
        try {
            if (message == null) {
                throw new IllegalArgumentException("Unsupported chat message");
            }
            switch (message) {
                case SystemMessage prompt -> currentPrompt = prompt;
                case UserMessage user -> {
                    if (user.contents().stream().anyMatch(content -> !(content instanceof TextContent))) {
                        throw new IllegalArgumentException("Only text user messages are supported");
                    }
                    endTurn();
                    if (user.hasSingleText()) {
                        var saved = await(conversation.addMessage(NewMessage.user(user.singleText())), waitTimeout);
                        turnTextIds.add(saved.id());
                    } else {
                        var parts = user.contents().stream()
                                .map(content -> NewMessage.user(((TextContent) content).text())).toList();
                        var saved = await(conversation.addMessages(parts), waitTimeout);
                        saved.forEach(part -> turnTextIds.add(part.id()));
                    }
                    turnMessages.add(user);
                }
                case AiMessage ai -> addAssistant(ai);
                case ToolExecutionResultMessage result -> addToolResult(result);
                default -> throw new IllegalArgumentException("Unsupported chat message");
            }
        } catch (RuntimeException | Error failure) {
            endTurn();
            throw failure;
        }
    }

    private void addAssistant(AiMessage ai) {
        if (!ai.images().isEmpty()) throw new IllegalArgumentException("Assistant images are unsupported");
        if (!ai.hasToolExecutionRequests()) {
            if (ai.text() != null && !ai.text().isBlank()) {
                await(conversation.addMessage(NewMessage.assistant(ai.text())), waitTimeout);
            }
            endTurn();
            return;
        }
        if (turnMessages.isEmpty()) throw new IllegalStateException("Tool requests require an active user turn");
        var ids = new HashSet<String>();
        for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
            if (request == null || request.id() == null || request.id().isBlank()
                    || !ids.add(request.id()) || toolRequests.containsKey(request.id())
                    || request.name() == null || request.name().isBlank()
                    || request.arguments() == null) {
                throw new IllegalArgumentException("Tool requests require unique nonblank IDs, names, and nonnull arguments");
            }
        }
        if (ai.text() != null && !ai.text().isBlank()) {
            var saved = await(conversation.addMessage(NewMessage.assistant(ai.text())), waitTimeout);
            turnTextIds.add(saved.id());
        }
        String action = "Call tools: " + ai.toolExecutionRequests().stream()
            .map(ToolExecutionRequest::name).collect(Collectors.joining(", "));
        var step = await(conversation.recordStep(new NewReasoningStep(
            "Assistant requested tool execution", action)), waitTimeout);
        for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
            toolRequests.put(request.id(), new RecordedToolRequest(step, request));
        }
        turnMessages.add(ai);
    }

    private void addToolResult(ToolExecutionResultMessage result) {
        if (!result.hasSingleText()) throw new IllegalArgumentException("Only single text tool results are supported");
        var tool = toolRequests.get(result.id());
        if (tool == null || !tool.request().name().equals(result.toolName())) {
            throw new IllegalArgumentException("Tool result must match a stored request ID and name");
        }
        var call = NewToolCall.builder(tool.request().name(), tool.request().arguments())
            .output(result.text())
            .status(Boolean.TRUE.equals(result.isError()) ? ToolCallStatus.ERROR : ToolCallStatus.SUCCESS)
            .build();
        await(tool.step().recordToolCall(call), waitTimeout);
        toolRequests.remove(result.id());
        turnMessages.add(result);
    }

    public void endTurn() {
        turnMessages.clear();
        turnTextIds.clear();
        toolRequests.clear();
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        throw new UnsupportedOperationException("History replacement is unsupported");
    }

    @Override
    public void set(ChatMessage... messages) {
        throw new UnsupportedOperationException("History replacement is unsupported");
    }

    @Override
    public void clear() {
        try {
            try {
                await(conversation.delete(), waitTimeout);
            } catch (MemoryServiceException failure) {
                if (failure.statusCode() != 404) throw failure;
            }
        } finally {
            endTurn();
        }
    }
}
