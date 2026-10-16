package org.neo4j.agentmemory.conformance;

import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

final class BridgeJson {
    private final JsonMapper mapper = JsonMapper.builder().build();

    static final class InvalidArgument extends IllegalArgumentException {
        InvalidArgument(String reason) { super(reason); }
    }

    tools.jackson.databind.node.ArrayNode array() { return mapper.createArrayNode(); }

    ObjectNode object() { return mapper.createObjectNode(); }

    ObjectNode read(byte[] bytes) {
        if (bytes.length == 0) return object();
        JsonNode node;
        try { node = mapper.readTree(bytes); }
        catch (RuntimeException invalid) { throw new InvalidArgument("unreadable body"); }
        if (!(node instanceof ObjectNode object)) {
            throw new InvalidArgument("body must be an object");
        }
        return object;
    }

    byte[] write(JsonNode node) { return mapper.writeValueAsBytes(node); }

    String required(ObjectNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.isString()) {
            throw new InvalidArgument(name + " must be a string");
        }
        return value.asString();
    }

    MessageRole role(ObjectNode body) {
        var role = required(body, "role");
        for (var candidate : MessageRole.values()) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(role)) return candidate;
        }
        throw new InvalidArgument("unknown role");
    }

    int limit(ObjectNode body, int fallback) {
        var value = body.get("limit");
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw new InvalidArgument("limit must be a positive integer");
        }
        return Math.min(value.asInt(), 200);
    }

    ObjectNode message(Message message) {
        return object().put("id", message.id().toString())
                .put("role", message.role().name().toLowerCase(Locale.ROOT))
                .put("content", message.content()).putNull("timestamp");
    }

    ObjectNode conversation(String session, Conversation conversation, List<Message> messages) {
        var result = object().put("id", conversation == null
                        ? "00000000-0000-0000-0000-000000000000" : conversation.id().toString())
                .put("session_id", session);
        var array = result.putArray("messages");
        messages.forEach(message -> array.add(message(message)));
        result.put("title", conversation == null ? null : conversation.title().orElse(null));
        result.put("created_at", conversation == null ? null
                : conversation.createdAt().map(Object::toString).orElse(null));
        result.put("updated_at", conversation == null ? null
                : conversation.updatedAt().map(Object::toString).orElse(null));
        return result;
    }

    ObjectNode session(Conversation conversation) {
        return object().put("session_id", conversation.metadata().get(Sessions.METADATA_KEY))
                .put("message_count", conversation.messageCount().orElse(0L))
                .put("created_at", conversation.createdAt().map(Object::toString).orElse(null))
                .put("updated_at", conversation.updatedAt().map(Object::toString).orElse(null));
    }

    String serviceCode(String excerpt) {
        try {
            var body = mapper.readTree(excerpt);
            var code = body == null ? null : body.get("error");
            return code != null && code.isString() ? " " + code.asString() : "";
        } catch (RuntimeException invalid) { return ""; }
    }
}
