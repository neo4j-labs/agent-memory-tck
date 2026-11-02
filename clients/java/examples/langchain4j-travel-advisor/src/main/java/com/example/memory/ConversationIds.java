package com.example.memory;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import java.util.UUID;

/** Parses the canonical UUID shared by conversation APIs and model memory. */
public final class ConversationIds {
    private ConversationIds() {}

    public static UUID parse(Object value) {
        try {
            String text = value.toString();
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "conversationId must be a UUID");
        }
    }
}
