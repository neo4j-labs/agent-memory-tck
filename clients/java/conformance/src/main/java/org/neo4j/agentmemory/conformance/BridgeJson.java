package org.neo4j.agentmemory.conformance;

import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    UUID uuid(ObjectNode body, String name) {
        var value = required(body, name);
        try {
            var id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException invalid) {
            // Report invalid UUIDs through the bridge's argument-error boundary.
        }
        throw new InvalidArgument(name + " must be a UUID");
    }

    NewToolCall newToolCall(ObjectNode body) {
        var arguments = body.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw new InvalidArgument("arguments must be an object");
        }
        var builder = NewToolCall.builder(required(body, "tool_name"), arguments.toString());
        var result = body.get("result");
        if (result != null && !result.isNull()) builder.output(result.toString());
        var status = body.get("status");
        if (status != null && !status.isNull()) {
            var found = false;
            for (var candidate : ToolCallStatus.values()) {
                if (status.isString() && candidate.name().toLowerCase(Locale.ROOT).equals(status.asString())) {
                    builder.status(candidate);
                    found = true;
                    break;
                }
            }
            if (!found) throw new InvalidArgument("unknown tool call status");
        }
        var duration = body.get("duration_ms");
        if (duration != null && !duration.isNull()) {
            if (!duration.isIntegralNumber() || !duration.canConvertToLong() || duration.asLong() < 0) {
                throw new InvalidArgument("duration_ms must be a non-negative integer in milliseconds");
            }
            builder.duration(Duration.ofMillis(duration.asLong()));
        }
        var error = body.get("error");
        if (error != null && !error.isNull()) {
            if (!error.isString()) throw new InvalidArgument("error must be a string");
            throw new BridgeMethods.Gap("service gap: tool call error has no hosted equivalent");
        }
        return builder.build();
    }

    ObjectNode toolCall(ToolCall call) {
        var result = object().put("id", call.id().toString()).put("tool_name", call.toolName())
                .put("status", call.status().name().toLowerCase(Locale.ROOT))
                .putNull("error")
                .set("arguments", mapper.readTree(call.input()))
                .set("result", call.output() == null ? null : mapper.readTree(call.output()));
        if (call.duration() == null) result.putNull("duration_ms");
        else result.put("duration_ms", call.duration().toMillis());
        return result;
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

    Map<String, String> metadata(ObjectNode body) {
        var metadata = body.get("metadata");
        if (metadata == null || metadata.isNull()) return Map.of();
        if (!metadata.isObject()) throw new InvalidArgument("metadata must be an object");
        var result = new HashMap<String, String>();
        for (var entry : metadata.properties()) {
            if (!entry.getValue().isString()) {
                throw new BridgeMethods.Gap("service gap: message metadata supports only string values");
            }
            result.put(entry.getKey(), entry.getValue().asString());
        }
        return result;
    }

    ObjectNode message(Message message) {
        return object().put("id", message.id().toString())
                .put("role", message.role().name().toLowerCase(Locale.ROOT))
                .put("content", message.content()).putNull("timestamp")
                .set("metadata", mapper.valueToTree(message.metadata()));
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
