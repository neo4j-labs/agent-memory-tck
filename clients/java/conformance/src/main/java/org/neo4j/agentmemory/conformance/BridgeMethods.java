package org.neo4j.agentmemory.conformance;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.NewMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BridgeMethods {
    private final Map<String, BridgeMethod> methods = new HashMap<>();

    BridgeMethods(MemoryClient client, BridgeJson json) {
        var sessions = new Sessions(client);
        methods.put("setup", body -> json.object().put("ok", true).put("protocol_version", "0.2.0"));
        methods.put("teardown", body -> null);
        methods.put("clear_all_data", body -> { sessions.forgetAll(); return null; });
        methods.put("clear_session", body -> {
            sessions.clear(json.required(body, "session_id"));
            return null;
        });
        methods.put("add_message", body -> {
            var session = json.required(body, "session_id");
            var message = new NewMessage(json.role(body), json.required(body, "content"), json.metadata(body));
            var conversation = sessions.bind(session);
            return json.message(MemoryClient.await(client.addMessage(conversation.id(), message),
                    Duration.ofSeconds(20)));
        });
        methods.put("get_conversation", body -> {
            var session = json.required(body, "session_id");
            var limit = json.limit(body, 200);
            var conversation = sessions.find(session);
            if (conversation.isEmpty()) return json.conversation(session, null, List.of());
            var messages = new ArrayList<>(MemoryClient.await(
                    client.messages(conversation.get().id(), limit), Duration.ofSeconds(20)));
            Collections.reverse(messages);
            return json.conversation(session, conversation.get(), messages);
        });
        methods.put("list_sessions", body -> {
            var result = json.array();
            sessions.all(json.limit(body, 100)).forEach(c -> result.add(json.session(c)));
            return result;
        });
        methods.put("record_tool_call", body -> {
            var stepId = json.uuid(body, "step_id");
            var call = json.newToolCall(body);
            return json.toolCall(MemoryClient.await(client.recordToolCall(stepId, call),
                    Duration.ofSeconds(20)));
        });
        for (var name : List.of("search_messages", "add_entity", "search_entities", "get_entity_by_name",
                "get_related_entities")) methods.put(name, clientGap(name));
        for (var name : List.of("delete_message", "add_preference", "add_fact", "search_preferences",
                "start_trace", "add_step", "complete_trace", "get_trace_with_steps", "list_traces",
                "get_tool_stats")) methods.put(name, serviceGap(name));
    }

    BridgeMethod find(String name) { return methods.get(name); }

    static final class Gap extends RuntimeException {
        Gap(String reason) { super(reason); }
    }

    private static BridgeMethod clientGap(String name) {
        return body -> { throw new Gap(
                "client gap: " + name + " is not yet supported by the Java client"); };
    }

    private static BridgeMethod serviceGap(String name) {
        return body -> { throw new Gap(
                "service gap: " + name + " has no hosted equivalent"); };
    }
}
