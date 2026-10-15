package org.neo4j.agentmemory.testsupport;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.wiremock.junit5.WireMockRequestResponseUtil;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public final class OpenApiContract {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SPEC_JSON = readSpec();
    private static final JsonNode SPEC = readJson(SPEC_JSON);
    private static final String TIMESTAMP = Instant.parse("2026-08-26T09:15:30Z").toString();
    private static final int MAX_DEPTH = 8;
    private static final OpenApiInteractionValidator VALIDATOR =
            OpenApiInteractionValidator.createForInlineApiSpecification(SPEC_JSON).build();

    private OpenApiContract() {}

    public static String spec() {
        return SPEC_JSON;
    }

    /** A WireMock response for one operation: its declared status and a fully populated body. */
    public static ResponseDefinitionBuilder response(String method, String path) {
        var responses = operation(method, path).get("responses");
        var status = declaredSuccessStatus(responses, method, path);
        var schema = responses.get(String.valueOf(status)).get("schema");
        var response = WireMock.aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json");
        return schema == null
                ? response
                : response.withBody(synthesize(schema, "", 0).toString());
    }

    /**
     * The contract cannot express this: no definition sets additionalProperties to false, so a
     * misspelled property name validates cleanly. Reject anything the request definition omits.
     */
    public static void assertOnlyDeclaredRequestProperties() {
        for (var event : WireMock.getAllServeEvents()) {
            var request = event.getRequest();
            var body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                continue;
            }
            var path = templateFor(request.getUrl());
            var schema = requestBodySchema(operation(request.getMethod().getName(), path));
            if (schema != null) {
                assertDeclared(readJson(body), schema, request.getMethod() + " " + path);
            }
        }
    }

    /**
     * WireMock validates asynchronously via its post-serve-action hook, well after this
     * assertion would run if it relied on that hook's accumulated report. Validate every
     * recorded exchange directly and synchronously instead, so there is no timing dependency.
     */
    public static void assertEveryExchangeMatchesTheContract() {
        for (var event : WireMock.getAllServeEvents()) {
            var request = event.getRequest();
            var report = VALIDATOR.validate(
                    WireMockRequestResponseUtil.toRequest(request),
                    WireMockRequestResponseUtil.toResponse(event.getResponse()));
            assertThat(report.hasErrors())
                    .as("%s %s violated the contract: %s",
                            request.getMethod(), request.getUrl(), report.getMessages())
                    .isFalse();
        }
    }

    private static void assertDeclared(JsonNode actual, JsonNode schema, String where) {
        var resolved = resolve(schema);
        if (actual.isArray()) {
            var items = resolved.get("items");
            if (items != null) {
                actual.forEach(element -> assertDeclared(element, items, where));
            }
            return;
        }
        if (!actual.isObject()) {
            return;
        }
        var properties = resolved.get("properties");
        var additional = resolved.get("additionalProperties");
        for (var entry : actual.properties()) {
            var child = properties == null ? null : properties.get(entry.getKey());
            if (child != null) {
                assertDeclared(entry.getValue(), child, where + "." + entry.getKey());
                continue;
            }
            assertThat(additional != null
                            && (additional.isObject() || (additional.isBoolean() && additional.asBoolean())))
                    .as("%s sends undeclared property '%s'", where, entry.getKey())
                    .isTrue();
            if (additional.isObject()) {
                assertDeclared(entry.getValue(), additional, where + "." + entry.getKey());
            }
        }
    }

    private static JsonNode synthesize(JsonNode schema, String name, int depth) {
        var resolved = resolve(schema);
        if (depth > MAX_DEPTH) {
            return MAPPER.createObjectNode();
        }
        if (resolved.has("enum")) {
            return resolved.get("enum").get(0);
        }
        var type = resolved.path("type").asText("string");
        if ("array".equals(type)) {
            var array = MAPPER.createArrayNode();
            array.add(synthesize(resolved.get("items"), name, depth + 1));
            return array;
        }
        var properties = resolved.get("properties");
        if (properties != null) {
            var object = MAPPER.createObjectNode();
            for (var entry : properties.properties()) {
                object.set(entry.getKey(), synthesize(entry.getValue(), entry.getKey(), depth + 1));
            }
            return object;
        }
        return MAPPER.valueToTree(scalar(type, name));
    }

    /**
     * The document declares no format on any property and no enum on any response property, so
     * these four conventions carry the constraints this client actually binds. Anything else keeps
     * the plain value its declared type implies.
     */
    private static Object scalar(String type, String name) {
        if (name.equals("id") || name.endsWith("Id") || name.endsWith("Ids")) {
            return UUID.randomUUID().toString();
        }
        if (name.equals("createdAt") || name.equals("updatedAt")) {
            return TIMESTAMP;
        }
        if (name.equals("role")) {
            return "user";
        }
        if (name.equals("status")) {
            return "success";
        }
        return switch (type) {
            case "integer" -> 1;
            case "number" -> 0.5d;
            case "boolean" -> true;
            case "object" -> java.util.Map.of();
            default -> name.isEmpty() ? "value" : name;
        };
    }

    private static JsonNode resolve(JsonNode schema) {
        var reference = schema.get("$ref");
        if (reference == null) {
            return schema;
        }
        var name = reference.asText().substring(reference.asText().lastIndexOf('/') + 1);
        var definition = SPEC.get("definitions").get(name);
        assertThat(definition).as("unresolved definition '%s'", name).isNotNull();
        return definition;
    }

    private static JsonNode operation(String method, String path) {
        var operation = SPEC.get("paths").path(path).get(method.toLowerCase(Locale.ROOT));
        assertThat(operation).as("%s %s is not declared in the contract", method, path).isNotNull();
        return operation;
    }

    private static JsonNode requestBodySchema(JsonNode operation) {
        for (var parameter : operation.path("parameters")) {
            if ("body".equals(parameter.path("in").asText(""))) {
                return parameter.get("schema");
            }
        }
        return null;
    }

    private static int declaredSuccessStatus(JsonNode responses, String method, String path) {
        var statuses = new ArrayList<Integer>();
        var codes = responses.fieldNames();
        while (codes.hasNext()) {
            var status = Integer.parseInt(codes.next());
            if (status >= 200 && status < 300) {
                statuses.add(status);
            }
        }
        if (statuses.isEmpty()) {
            return fail("%s %s declares no success response", method, path);
        }
        return statuses.stream().min(Integer::compareTo).orElseThrow();
    }

    /** Match a concrete request URL back to the templated path that declares it. */
    private static String templateFor(String url) {
        var actual = url.split("\\?", 2)[0].split("/");
        var candidates = SPEC.get("paths").fieldNames();
        while (candidates.hasNext()) {
            var candidate = candidates.next();
            var template = candidate.split("/");
            if (template.length != actual.length) {
                continue;
            }
            var matches = true;
            for (var i = 0; i < template.length; i++) {
                if (!template[i].startsWith("{") && !template[i].equals(actual[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return candidate;
            }
        }
        return fail("no declared path matches %s", url);
    }

    private static JsonNode readJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String readSpec() {
        try (var stream = OpenApiContract.class.getResourceAsStream("/openapi.json")) {
            assertThat(stream).as("openapi.json is missing from the test classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
