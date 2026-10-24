package org.neo4j.agentmemory.conformance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the public bridge protocol against a stub of the hosted service. */
class MessageMetadataBridgeTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONVERSATION_ID = "a2f55d70-838f-4c41-ae7d-dad30fd25720";
    private static final String MESSAGE = """
            {"id":"3b3af5b6-2bff-41fc-bdb2-c72e63822d47","role":"user","content":"hello",
             "metadata":{"source":"service","priority":"high"}}
            """;
    private static final AtomicReference<JsonNode> LAST_MESSAGE_REQUEST = new AtomicReference<>();
    private static final AtomicInteger SERVICE_CALLS = new AtomicInteger();
    @TempDir static Path directory;
    private static HttpServer service;
    private static BridgeProcess bridge;

    @BeforeAll
    static void startBridge() throws Exception {
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/v1/", MessageMetadataBridgeTest::serve);
        service.start();
        bridge = new BridgeProcess("http://127.0.0.1:" + service.getAddress().getPort() + "/v1",
                directory.resolve("bridge.log"));
    }

    @AfterAll
    static void stopBridge() throws Exception {
        try {
            if (bridge != null) bridge.close();
        } finally {
            if (service != null) service.stop(0);
        }
    }


    @Test
    void forwardsStringMetadataAndReturnsServiceMetadataOnAddAndRead() throws Exception {
        var added = call("add_message", """
                {"session_id":"metadata","role":"user","content":"hello",
                 "metadata":{"source":"test","priority":"high"}}
                """);
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(LAST_MESSAGE_REQUEST.get().get("metadata")).isEqualTo(JSON.readTree("""
                {"source":"test","priority":"high"}
                """));
        var expected = JSON.readTree("""
                {"source":"service","priority":"high"}
                """);
        assertThat(JSON.readTree(added.body()).get("metadata")).isEqualTo(expected);

        var read = call("get_conversation", "{\"session_id\":\"metadata\"}");
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(read.body()).get("messages").get(0).get("metadata")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"metadata\":null", ",\"metadata\":{}"})
    void acceptsAbsentNullAndEmptyMetadata(String metadata) throws Exception {
        var response = call("add_message", """
                {"session_id":"empty","role":"user","content":"hello"%s}
                """.formatted(metadata));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(LAST_MESSAGE_REQUEST.get().has("metadata")).isFalse();
        assertThat(JSON.readTree(response.body()).get("metadata")).isEqualTo(JSON.createObjectNode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "1.5", "true", "null", "[]", "{\"nested\":\"value\"}"})
    void reportsUnsupportedValuesBeforeCallingTheService(String value) throws Exception {
        int before = SERVICE_CALLS.get();
        var response = call("add_message", """
                {"session_id":"unsupported","role":"user","content":"hello",
                 "metadata":{"source":"test","unsupported":%s}}
                """.formatted(value));
        assertThat(response.statusCode()).isEqualTo(501);
        assertThat(JSON.readTree(response.body()).get("error").asString())
                .isEqualTo("service gap: message metadata supports only string values");
        assertThat(SERVICE_CALLS.get()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "true", "[]", "\"text\""})
    void rejectsNonObjectMetadataBeforeCallingTheService(String value) throws Exception {
        int before = SERVICE_CALLS.get();
        var response = call("add_message", """
                {"session_id":"invalid","role":"user","content":"hello","metadata":%s}
                """.formatted(value));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").asString())
                .isEqualTo("add_message: metadata must be an object");
        assertThat(SERVICE_CALLS.get()).isEqualTo(before);
    }

    private static HttpResponse<String> call(String method, String body) throws Exception {
        return bridge.call(method, body);
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            SERVICE_CALLS.incrementAndGet();
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            String response;
            int status = 200;
            if (path.equals("/v1/conversations") && method.equals("GET")) {
                response = "{\"conversations\":[]}";
            } else if (path.equals("/v1/conversations") && method.equals("POST")) {
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                response = JSON.createObjectNode().put("id", CONVERSATION_ID)
                        .set("metadata", request.get("metadata")).toString();
                status = 201;
            } else if (path.equals("/v1/conversations/" + CONVERSATION_ID + "/messages")) {
                if (method.equals("POST")) {
                    LAST_MESSAGE_REQUEST.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                    response = LAST_MESSAGE_REQUEST.get().has("metadata")
                            ? MESSAGE : MESSAGE.replace("{\"source\":\"service\",\"priority\":\"high\"}", "{}");
                    status = 201;
                } else {
                    response = "{\"messages\":[" + MESSAGE + "]}";
                }
            } else {
                response = "{}";
                status = 404;
            }
            var bytes = response.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
