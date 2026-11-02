package com.example.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;

class NamsToolCallbackTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext originalContext = new ToolContext(Map.of("permission", "original"));
    private ToolCallback delegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name("probe").description("probe").inputSchema("{}").build());
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(delegate.call("{}", originalContext)).thenReturn("actual result");
        return delegate;
    }
    @Test void delegatesSchemaMetadataContextAndCapturesSuccessfulExecution() throws Exception {
        var delegate = delegate(); List<NewToolCall> attempts = new ArrayList<>();
        var callback = new NamsToolCallback(delegate, attempts::add, mapper);
        assertSame(delegate.getToolDefinition(), callback.getToolDefinition());
        assertSame(delegate.getToolMetadata(), callback.getToolMetadata());
        assertEquals("actual result", callback.call("{}", originalContext));
        verify(delegate).call("{}", originalContext);
        assertEquals("probe", attempts.getFirst().toolName());
        assertEquals("{}", attempts.getFirst().input());
        assertEquals("actual result", mapper.readTree(attempts.getFirst().output()).asText());
        assertEquals(ToolCallStatus.SUCCESS, attempts.getFirst().status());
        assertFalse(attempts.getFirst().duration().isNegative());
    }
    @Test void callbackPreservesOutcomeWhenEvidenceEncodingOrCaptureFails() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) throws JsonProcessingException {
                throw JsonMappingException.fromUnexpectedIOE(new IOException("private details"));
            }
        };
        for (ObjectMapper encoder : List.of(mapper, failingMapper)) {
            var delegate = delegate(); Consumer<NewToolCall> capture = call -> { throw new IllegalStateException("private capture"); };
            var callback = new NamsToolCallback(delegate, capture, encoder);
            assertEquals("actual result", callback.call("{}", originalContext));
            verify(delegate).call("{}", originalContext);
            RuntimeException originalFailure = new IllegalArgumentException("private failure");
            when(delegate.call("{}", originalContext)).thenThrow(originalFailure);
            assertSame(originalFailure, assertThrows(RuntimeException.class, () -> callback.call("{}", originalContext)));
        }
    }
    @Test void errorsDiscloseOnlyExceptionClassAndNormalErrorTextIsSuccess() throws Exception {
        var delegate = delegate(); List<NewToolCall> attempts = new ArrayList<>();
        var callback = new NamsToolCallback(delegate, attempts::add, mapper);
        RuntimeException originalFailure = new IllegalArgumentException("private failure");
        when(delegate.call("{}", originalContext)).thenThrow(originalFailure);
        assertSame(originalFailure, assertThrows(RuntimeException.class, () -> callback.call("{}", originalContext)));
        assertEquals(ToolCallStatus.ERROR, attempts.getFirst().status());
        assertEquals(originalFailure.getClass().getName(), mapper.readTree(attempts.getFirst().output()).asText());
        assertFalse(attempts.toString().contains("private failure"));
        doReturn("error: unavailable").when(delegate).call("{}", originalContext);
        callback.call("{}", originalContext);
        assertEquals(ToolCallStatus.SUCCESS, attempts.getLast().status());
        assertEquals("error: unavailable", mapper.readTree(attempts.getLast().output()).asText());
    }
    @Test void noContextOverloadPreservesDelegateSemanticsAndCaptures() {
        var delegate = delegate(); List<NewToolCall> attempts = new ArrayList<>();
        when(delegate.call("{}")).thenReturn("one argument");
        var callback = new NamsToolCallback(delegate, attempts::add, mapper);
        assertEquals("one argument", callback.call("{}")); verify(delegate).call("{}");
        verify(delegate, never()).call(eq("{}"), any()); assertEquals(1, attempts.size());
    }
}
