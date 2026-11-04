package com.example.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamsClientExceptionHandlerTest {
    private final ErrorResponseProcessor<Object> processor = (context, response) ->
        response.body(Map.of("message", context.getErrors().getFirst().getMessage()));
    private final HttpRequest<?> request = HttpRequest.GET("/api/conversations");

    @Test
    void mapsTimeoutWithoutExposingNativeFailureDetails() {
        var handler = new NamsClientExceptionHandler(processor);
        var cause = new TimeoutException("secret transport detail");
        var failure = new MemoryClientException("secret upstream response", cause);
        var response = handler.handle(request, failure);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatus());
        assertEquals(Map.of("message", "NAMS operation timed out"), response.getBody().orElseThrow());
        assertSame(cause, failure.getCause());
    }

    @Test
    void mapsConversationScoped404() {
        for (String operation : List.of("getConversation", "addMessage", "messages", "context", "recordStep", "recordToolCall", "deleteConversation")) {
            var failure = service(operation, 404);
            var response = new NamsClientExceptionHandler(processor).handle(request, failure);
            assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
            assertEquals(Map.of("message", "Conversation not found"), response.getBody().orElseThrow());
        }
    }

    @Test
    void nonConversation404IsBadGateway() {
        for (String operation : List.of("createConversation", "listConversations")) {
            var response = new NamsClientExceptionHandler(processor).handle(request, service(operation, 404));
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
            assertEquals(Map.of("message", "NAMS operation failed"), response.getBody().orElseThrow());
        }
    }

    @Test
    void service500AndDecodingFailureAreBadGateway() {
        var handler = new NamsClientExceptionHandler(processor);
        for (var failure : List.of(service("messages", 500), new MemoryClientException("secret decode failure"))) {
            var response = handler.handle(request, failure);
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
            assertEquals(Map.of("message", "NAMS operation failed"), response.getBody().orElseThrow());
        }
    }

    @Test
    void interruptionIsBadGatewayAndPreservesCause() {
        var cause = new InterruptedException("secret interrupt");
        var failure = new MemoryClientException("secret upstream response", cause);
        var response = new NamsClientExceptionHandler(processor).handle(request, failure);
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
        assertEquals(Map.of("message", "NAMS operation interrupted"), response.getBody().orElseThrow());
        assertSame(cause, failure.getCause());
    }

    @Test
    void nativeAwaitRestoresInterruptBeforeHttpMapping() {
        Thread.currentThread().interrupt();
        try {
            var failure = assertThrows(MemoryClientException.class,
                () -> MemoryClient.await(new CompletableFuture<>(), Duration.ofSeconds(1)));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            var response = new NamsClientExceptionHandler(processor).handle(request, failure);
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
            assertEquals(Map.of("message", "NAMS operation interrupted"), response.getBody().orElseThrow());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static MemoryServiceException service(String operation, int code) {
        return new MemoryServiceException(operation, code, Map.of(), "text/plain", "secret response");
    }
}
