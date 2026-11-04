package com.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Owns one execution round's evidence while Spring AI owns tool execution and history. */
public final class NamsToolCallingManager implements ToolCallingManager {
    private static final Logger log = LoggerFactory.getLogger(NamsToolCallingManager.class);
    private final ToolCallingManager delegate;
    private final NamsChatMemory memory;
    private final ObjectMapper mapper;

    public NamsToolCallingManager(ToolCallingManager delegate, NamsChatMemory memory, ObjectMapper mapper) {
        this.delegate = Objects.requireNonNull(delegate);
        this.memory = Objects.requireNonNull(memory);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        AssistantMessage selected = response.getResults().stream().map(generation -> generation.getOutput())
                .filter(AssistantMessage::hasToolCalls).findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool call requested by the chat model"));
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            throw new IllegalArgumentException("Tool calling options are required");
        }
        if (options.getToolContext() == null
                || !(options.getToolContext().get(ChatMemory.CONVERSATION_ID) instanceof String id)
                || id.isBlank()) {
            throw new IllegalArgumentException("Conversation ID is required in tool context");
        }
        if (selected.getText() != null && !selected.getText().isBlank()) {
            memory.add(id, List.of(selected));
        }
        List<NewToolCall> attempts = new ArrayList<>();
        List<ToolCallback> callbacks = options.getToolCallbacks() == null ? List.of() : options.getToolCallbacks();
        List<ToolCallback> wrapped = callbacks.stream()
                .map(callback -> (ToolCallback) new NamsToolCallback(callback, attempts::add, mapper)).toList();
        var copiedOptions = options.mutate().toolCallbacks(wrapped).build();
        ToolExecutionResult result;
        try {
            result = delegate.executeToolCalls(new Prompt(prompt.getInstructions(), copiedOptions), response);
        } catch (RuntimeException failure) {
            finalizeRound(id, List.copyOf(attempts), null);
            throw failure;
        }
        finalizeRound(id, List.copyOf(attempts), result);
        return result;
    }

    private void finalizeRound(String id, List<NewToolCall> attempts, ToolExecutionResult result) {
        try {
            if (result == null) {
                memory.recordToolRound(id, attempts);
            } else {
                var actual = (ToolResponseMessage) result.conversationHistory().getLast();
                var metadata = new HashMap<>(actual.getMetadata());
                metadata.put(NamsChatMemory.TOOL_CALLS_METADATA_KEY, attempts);
                var recordingCopy = ToolResponseMessage.builder().responses(actual.getResponses()).metadata(metadata).build();
                memory.add(id, List.of(recordingCopy));
            }
        } catch (RuntimeException failure) {
            log.warn("NAMS round evidence recording failed ({})", failure.getClass().getName());
        }
    }
}
