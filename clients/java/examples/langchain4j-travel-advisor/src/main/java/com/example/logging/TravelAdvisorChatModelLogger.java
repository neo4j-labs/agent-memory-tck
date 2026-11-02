package com.example.logging;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.Collectors;

@Requires(property = "app.ai.pretty-logging.enabled", value = "true", defaultValue = "true")
@Singleton
public class TravelAdvisorChatModelLogger implements ChatModelListener {

    private static final Logger LOG = LoggerFactory.getLogger(TravelAdvisorChatModelLogger.class);
    private static final String PREFIX = "[ai]";
    private static final int MAX_LOG_TEXT_LENGTH = 280;

    @Override
    public void onRequest(ChatModelRequestContext context) {
        List<ChatMessage> messages = context.chatRequest().messages();
        if (messages.isEmpty()) {
            return;
        }

        var messageCounts = new EnumMap<ChatMessageType, Integer>(ChatMessageType.class);
        for (ChatMessage message : messages) {
            messageCounts.merge(message.type(), 1, Integer::sum);
        }
        LOG.info("{} request: {} messages {}", PREFIX, messages.size(), messageCounts);
        // Inspect the actual model input, including history and any appended NAMS context.
        // Keep full content at DEBUG so the system prompt's tail is not truncated.
        if (LOG.isDebugEnabled()) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                LOG.debug("{} request message {}/{} [{}]: {}", PREFIX,
                    i + 1, messages.size(), message.type(), message);
            }
        }

        ChatMessage lastMessage = messages.get(messages.size() - 1);
        if (lastMessage instanceof UserMessage userMessage && userMessage.hasSingleText()) {
            List<ToolSpecification> toolSpecifications = context.chatRequest().toolSpecifications();
            LOG.info("{} user: {}", PREFIX, summarize(userMessage.singleText()));
            LOG.info("{} tools available{}: {}", PREFIX,
                pluralize(toolSpecifications == null ? 0 : toolSpecifications.size()),
                formatToolSpecifications(toolSpecifications));
            return;
        }

        List<ToolExecutionResultMessage> toolResults = trailingToolResults(messages);
        for (ToolExecutionResultMessage toolResult : toolResults) {
            LOG.info("{} tool result: {} -> {}", PREFIX, toolResult.toolName(), summarize(toolResult.text()));
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        AiMessage aiMessage = context.chatResponse().aiMessage();
        if (aiMessage == null) {
            return;
        }

        if (aiMessage.hasToolExecutionRequests()) {
            String toolRequests = aiMessage.toolExecutionRequests().stream()
                .map(this::formatToolRequest)
                .collect(Collectors.joining(" | "));
            LOG.info("{} tool call{}: {}", PREFIX, pluralize(aiMessage.toolExecutionRequests().size()), toolRequests);
            return;
        }

        if (hasText(aiMessage.text())) {
            LOG.info("{} assistant{}: {}", PREFIX, formatTokenUsage(context.chatResponse().tokenUsage()), summarize(aiMessage.text()));
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        LOG.warn("{} model error", PREFIX, context.error());
    }

    private List<ToolExecutionResultMessage> trailingToolResults(List<ChatMessage> messages) {
        List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof ToolExecutionResultMessage toolResult) {
                toolResults.add(toolResult);
            } else {
                break;
            }
        }
        Collections.reverse(toolResults);
        return toolResults;
    }

    private String formatToolRequest(ToolExecutionRequest request) {
        String arguments = summarize(request.arguments());
        if (!hasText(arguments) || "{}".equals(arguments)) {
            return request.name() + "()";
        }
        return request.name() + "(" + arguments + ")";
    }

    private String formatToolSpecifications(List<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return "none";
        }

        return toolSpecifications.stream()
            .map(ToolSpecification::name)
            .collect(Collectors.joining(", "));
    }

    private String formatTokenUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null || tokenUsage.totalTokenCount() == null) {
            return "";
        }
        return " [" + tokenUsage.totalTokenCount() + " tok]";
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String pluralize(int count) {
        return count == 1 ? "" : "s";
    }

    private String summarize(String text) {
        if (!hasText(text)) {
            return "";
        }

        String normalized = text
            .replace("\r", "")
            .replace("\n", " | ")
            .replaceAll("\\s+", " ")
            .trim();

        if (normalized.length() <= MAX_LOG_TEXT_LENGTH) {
            return normalized;
        }

        return normalized.substring(0, MAX_LOG_TEXT_LENGTH - 3) + "...";
    }
}
