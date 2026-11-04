package com.example.controller;

import java.util.Map;
import java.util.Set;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ChatController.class)
public class NamsExceptionHandler {
    private static final Set<String> CONVERSATION_OPERATIONS = Set.of("getConversation", "messages", "context", "addMessage", "addMessages");
    @ExceptionHandler(MemoryClientException.class)
    public ResponseEntity<Map<String, String>> handle(MemoryClientException failure) {
        if (failure instanceof MemoryServiceException service && service.statusCode() == 404
                && CONVERSATION_OPERATIONS.contains(service.operation())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Conversation not found"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Memory service unavailable"));
    }
}
