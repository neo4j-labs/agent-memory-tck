package org.neo4j.agentmemory.conformance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallBridgeTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STEP_ID = "a2f55d70-838f-4c41-ae7d-dad30fd25720";
    private static final String CALL_ID = "3b3af5b6-2bff-41fc-bdb2-c72e63822d47";
    private static final AtomicReference<JsonNode> REQUEST = new AtomicReference<>();
    private static final AtomicInteger SERVICE_CALLS = new AtomicInteger();
    @TempDir static Path directory;
    private static HttpServer service;
    private static BridgeProcess bridge;

    @BeforeAll
    static void start() throws Exception {
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/v1/reasoning/tool-calls", exchange -> {
            try (exchange) {
                SERVICE_CALLS.incrementAndGet();
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                REQUEST.set(request);
                var response = JSON.createObjectNode().put("id", CALL_ID).put("stepId", STEP_ID)
                        .put("toolName", "stored_lookup").set("status", request.get("status"));
                var bytes = JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        service.start();
        bridge = new BridgeProcess("http://127.0.0.1:" + service.getAddress().getPort() + "/v1",
                directory.resolve("bridge.log"));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (bridge != null) bridge.close();
        } finally {
            if (service != null) service.stop(0);
        }
    }

    @Test
    void translatesStructuredPayloadsAndDurationThroughTheJavaClient() throws Exception {
        var response = bridge.call("record_tool_call", """
                {"step_id":"%s","tool_name":"lookup",
                 "arguments":{"query":"Alice","nested":{"tags":["a","b"],"active":true,"optional":null}},
                 "result":{"found":true,"count":2},"duration_ms":150}
                """.formatted(STEP_ID));
        assertThat(response.statusCode()).isEqualTo(200);
        var request = REQUEST.get();
        assertThat(request.get("stepId").asString()).isEqualTo(STEP_ID);
        assertThat(request.get("toolName").asString()).isEqualTo("lookup");
        assertThat(request.get("input").isString()).isTrue();
        assertThat(JSON.readTree(request.get("input").asString())).isEqualTo(JSON.readTree("""
                {"query":"Alice","nested":{"tags":["a","b"],"active":true,"optional":null}}
                """));
        assertThat(request.get("output").isString()).isTrue();
        assertThat(JSON.readTree(request.get("output").asString())).isEqualTo(JSON.readTree("{\"found\":true,\"count\":2}"));
        assertThat(request.get("status").asString()).isEqualTo("success");
        assertThat(request.get("durationMs").asLong()).isEqualTo(150);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree("""
                {"id":"%s","tool_name":"stored_lookup",
                 "arguments":{"query":"Alice","nested":{"tags":["a","b"],"active":true,"optional":null}},
                 "result":{"found":true,"count":2},"status":"success","duration_ms":150,"error":null}
                """.formatted(CALL_ID)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "success", "failure", "error", "timeout", "cancelled"})
    void translatesAllStatusesWithoutAnErrorField(String status) throws Exception {
        var body = input().put("status", status);
        var response = bridge.call("record_tool_call", body.toString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(REQUEST.get().get("status").asString()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).get("status").asString()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1,null,true]", "\"a string with \\n and 世界\"", "42", "1.5", "false", "null"})
    void preservesJsonResultTypes(String result) throws Exception {
        var body = input().set("result", JSON.readTree(result));
        var response = bridge.call("record_tool_call", body.toString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("result")).isEqualTo(JSON.readTree(result));
        if (result.equals("null")) assertThat(REQUEST.get().has("output")).isFalse();
        else assertThat(JSON.readTree(REQUEST.get().get("output").asString())).isEqualTo(JSON.readTree(result));
    }

    @Test
    void defaultsStatusAndPreservesAbsentOptionalFields() throws Exception {
        var response = bridge.call("record_tool_call", input().toString());
        assertThat(response.statusCode()).isEqualTo(200);
        var request = REQUEST.get();
        assertThat(request.get("input").asString()).isEqualTo("{}");
        assertThat(request.get("status").asString()).isEqualTo("success");
        assertThat(request.has("output")).isFalse();
        assertThat(request.has("durationMs")).isFalse();
        var result = JSON.readTree(response.body());
        assertThat(result.get("arguments")).isEqualTo(JSON.createObjectNode());
        assertThat(result.get("result").isNull()).isTrue();
        assertThat(result.get("duration_ms").isNull()).isTrue();
        assertThat(result.get("error").isNull()).isTrue();
    }

    @Test
    void acceptsNullOptionalsAndZeroDuration() throws Exception {
        var body = input().putNull("status").putNull("error").putNull("result").putNull("duration_ms");
        var response = bridge.call("record_tool_call", body.toString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(REQUEST.get().has("durationMs")).isFalse();
        body.put("duration_ms", 0);
        response = bridge.call("record_tool_call", body.toString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(REQUEST.get().get("durationMs").asLong()).isZero();
        assertThat(JSON.readTree(response.body()).get("duration_ms").asLong()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Connection refused", ""})
    void reportsTheErrorFieldAsAServiceGapBeforeWriting(String error) throws Exception {
        int before = SERVICE_CALLS.get();
        var response = bridge.call("record_tool_call", input().put("error", error).toString());
        assertThat(response.statusCode()).isEqualTo(501);
        assertThat(JSON.readTree(response.body()).get("error").asString())
                .isEqualTo("service gap: tool call error has no hosted equivalent");
        assertThat(SERVICE_CALLS.get()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"step_id\":\"not-a-uuid\"}", "{\"step_id\":\"1-1-1-1-1\"}", "{\"step_id\":null}",
            "{\"arguments\":null}", "{\"arguments\":[]}", "{\"arguments\":\"{}\"}",
            "{\"tool_name\":42}", "{\"status\":\"unknown\"}", "{\"status\":1}",
            "{\"duration_ms\":-1}", "{\"duration_ms\":1.5}", "{\"duration_ms\":\"150\"}",
            "{\"duration_ms\":9223372036854775808}", "{\"error\":{}}"
    })
    void rejectsInvalidArgumentsBeforeWriting(String override) throws Exception {
        int before = SERVICE_CALLS.get();
        var body = input().setAll((ObjectNode) JSON.readTree(override));
        var response = bridge.call("record_tool_call", body.toString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").asString()).startsWith("record_tool_call: ");
        assertThat(SERVICE_CALLS.get()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"step_id", "tool_name", "arguments"})
    void rejectsMissingRequiredFieldsBeforeWriting(String field) throws Exception {
        int before = SERVICE_CALLS.get();
        var body = input();
        body.remove(field);
        assertThat(bridge.call("record_tool_call", body.toString()).statusCode()).isEqualTo(400);
        assertThat(SERVICE_CALLS.get()).isEqualTo(before);
    }

    private static ObjectNode input() {
        return JSON.createObjectNode().put("step_id", STEP_ID).put("tool_name", "lookup")
                .set("arguments", JSON.createObjectNode());
    }
}
