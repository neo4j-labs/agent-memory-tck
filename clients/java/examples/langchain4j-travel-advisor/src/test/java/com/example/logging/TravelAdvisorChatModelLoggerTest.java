package com.example.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelAdvisorChatModelLoggerTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(TravelAdvisorChatModelLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void debugShowsFullSystemContextAndHistoryInRequestOrder() {
        logger.setLevel(Level.DEBUG);
        String prompt = "Travel instructions. ".repeat(30)
            + "\nNAMS_CONTEXT_JSON:\n{\"observations\":[{\"content\":\"Prefers quiet lakeside hotels\"}]}";
        String answer = "Hotel description. ".repeat(30) + "Old Town Guesthouse: 120 CHF/night.";

        logRequest(List.of(SystemMessage.from(prompt),
            UserMessage.from("Find me a hotel near Lucerne under 250 CHF"),
            AiMessage.from(answer), UserMessage.from("Where were we?")));

        assertTrue(logs(Level.INFO).contains("[ai] request: 4 messages {SYSTEM=1, USER=2, AI=1}"));
        var details = logs(Level.DEBUG);
        assertEquals(4, details.size());
        assertTrue(details.get(0).contains("request message 1/4 [SYSTEM]"));
        assertTrue(details.get(0).contains(prompt));
        assertTrue(details.get(1).contains("Find me a hotel near Lucerne under 250 CHF"));
        assertTrue(details.get(2).contains(answer));
        assertTrue(details.get(3).contains("Where were we?"));
    }

    @Test
    void debugShowsRefreshedContextAndToolExchangeOnFollowUpRequest() {
        logger.setLevel(Level.DEBUG);
        logRequest(List.of(SystemMessage.from("Earlier context"), UserMessage.from("Check my wishlist")));
        appender.list.clear();
        var tool = ToolExecutionRequest.builder().id("wishlist_call")
            .name("getWishlist").arguments("{}").build();

        logRequest(List.of(SystemMessage.from("Updated context: prefers Lucerne"),
            UserMessage.from("Check my wishlist"), AiMessage.from(tool),
            ToolExecutionResultMessage.from(tool, "Your wishlist is empty.")));

        assertTrue(logs(Level.INFO).contains(
            "[ai] request: 4 messages {SYSTEM=1, USER=1, AI=1, TOOL_EXECUTION_RESULT=1}"));
        assertTrue(logs(Level.INFO).contains("[ai] tool result: getWishlist -> Your wishlist is empty."));
        var details = logs(Level.DEBUG);
        assertEquals(4, details.size());
        assertTrue(details.get(0).contains("Updated context: prefers Lucerne"));
        assertFalse(details.get(0).contains("Earlier context"));
        assertTrue(details.get(2).contains("wishlist_call"));
        assertTrue(details.get(2).contains("getWishlist"));
        assertTrue(details.get(3).contains("wishlist_call"));
        assertTrue(details.get(3).contains("Your wishlist is empty."));
    }

    @Test
    void infoShowsRequestCompositionWithoutDumpingSystemPromptOrHistory() {
        logger.setLevel(Level.INFO);

        logRequest(List.of(SystemMessage.from("System prompt with remembered preferences"),
            UserMessage.from("Earlier question about Lucerne"), AiMessage.from("Earlier hotel recommendation"),
            UserMessage.from("Where were we?")));

        assertEquals(List.of("[ai] request: 4 messages {SYSTEM=1, USER=2, AI=1}",
            "[ai] user: Where were we?", "[ai] tools availables: none"), logs(Level.INFO));
        assertTrue(logs(Level.DEBUG).isEmpty());
    }

    private void logRequest(List<ChatMessage> messages) {
        var request = ChatRequest.builder().messages(messages).build();
        new TravelAdvisorChatModelLogger().onRequest(
            new ChatModelRequestContext(request, null, new HashMap<>()));
    }

    private List<String> logs(Level level) {
        return appender.list.stream().filter(event -> event.getLevel().equals(level))
            .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
