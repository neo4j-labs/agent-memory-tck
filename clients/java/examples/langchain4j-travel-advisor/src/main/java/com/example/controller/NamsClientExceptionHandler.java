package com.example.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;

/** Converts native NAMS failures into safe HTTP responses at the HTTP boundary. */
@Singleton
public final class NamsClientExceptionHandler
        implements ExceptionHandler<MemoryClientException, HttpResponse<?>> {
    private final ErrorResponseProcessor<?> processor;

    public NamsClientExceptionHandler(ErrorResponseProcessor<?> processor) {
        this.processor = processor;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, MemoryClientException failure) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        String message = "NAMS operation failed";
        if (failure.getCause() instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "NAMS operation timed out";
        } else if (failure.getCause() instanceof InterruptedException) {
            message = "NAMS operation interrupted";
        } else if (failure instanceof MemoryServiceException service
                && service.statusCode() == 404
                && Set.of("getConversation", "addMessage", "messages", "context", "recordStep",
                    "recordToolCall", "deleteConversation")
                    .contains(service.operation())) {
            status = HttpStatus.NOT_FOUND;
            message = "Conversation not found";
        }
        return processor.processResponse(ErrorContext.builder(request).cause(failure)
            .errorMessage(message).build(), HttpResponse.status(status));
    }
}
