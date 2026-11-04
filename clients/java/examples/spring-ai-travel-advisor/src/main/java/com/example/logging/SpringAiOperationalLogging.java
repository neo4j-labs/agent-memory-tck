package com.example.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Logs actual Spring AI model rounds and tool executions, independently of tracing/export. */
@Component
@ConditionalOnProperty(name = "app.ai.pretty-logging.enabled", havingValue = "true", matchIfMissing = true)
public final class SpringAiOperationalLogging implements ObservationHandler<Observation.Context> {
    private static final Logger LOG = LoggerFactory.getLogger(SpringAiOperationalLogging.class);
    private final boolean includeContent;

    public SpringAiOperationalLogging(@Value("${app.ai.pretty-logging.include-content:false}") boolean includeContent) {
        this.includeContent = includeContent;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext || context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        if (context instanceof ChatModelObservationContext chat) {
            var messages = chat.getRequest().getInstructions();
            if (!messages.isEmpty() && messages.getLast() instanceof UserMessage user) {
                LOG.info("[ai] user: {}", content(user.getText()));
            }
        } else if (context instanceof ToolCallingObservationContext tool) {
            LOG.info("[ai] tool call: {} ({})", tool.getToolDefinition().name(), content(tool.getToolCallArguments()));
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        if (context.getError() != null) {
            return;
        }
        if (context instanceof ChatModelObservationContext chat && chat.getResponse() != null) {
            var response = chat.getResponse();
            var usage = response.getMetadata().getUsage();
            String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
            LOG.info("[ai] assistant [input={}, output={}, total={} tok]: {}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(), content(text));
        } else if (context instanceof ToolCallingObservationContext tool) {
            LOG.info("[ai] tool result: {} -> {}", tool.getToolDefinition().name(), content(tool.getToolCallResult()));
        }
    }

    @Override
    public void onError(Observation.Context context) {
        // Provider exceptions can contain response bodies or credentials: record only the error type.
        if (context.getError() != null) {
            LOG.warn("[ai] {} error: {}", context instanceof ChatModelObservationContext ? "model" : "tool",
                    context.getError().getClass().getSimpleName());
        }
    }

    private String content(String text) {
        if (!includeContent) {
            return "[content disabled]";
        }
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 277) + "...";
    }
}
