package org.neo4j.agentmemory.http;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.*;
import org.neo4j.agentmemory.entity.Entity;
import org.neo4j.agentmemory.entity.EntitySearch;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.exception.ResponseDecodingException;
import org.neo4j.agentmemory.reasoning.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * HTTP memory operations. Internal implementation and not part of the
 * {@link HttpTransport} SPI; use MemoryClient factories.
 */
public final class HttpMemoryClient implements MemoryClient {
    private static final int BODY_EXCERPT_LIMIT = 1024;

    private final URI endpoint;
    private final String apiKey;
    private final HttpTransport transport;
    private final JsonCodec jsonCodec;
    private final ClientLogging logging;

    HttpMemoryClient(URI endpoint, String apiKey, HttpTransport transport, JsonCodec jsonCodec, ClientLogging logging) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.transport = transport;
        this.jsonCodec = jsonCodec;
        this.logging = logging;
        logging.initialized(endpoint, transport.name(), jsonCodec.name());
    }

    public static MemoryClient create(MemoryClientConfiguration configuration) {
        var jsonCodec = JsonCodecs.select();
        return new HttpMemoryClient(configuration.baseUrl(), configuration.apiKey(),
                configuration.httpTransport(), jsonCodec, new ClientLogging());
    }

    @Override
    public CompletableFuture<Conversation> createConversation(CreateConversation request) {
        var body = new CreateConversationRequest(
                request.userId(), request.metadata().isEmpty() ? null : request.metadata());
        return post(
                "createConversation",
                "/conversations", Map.of(),
                body,
                ConversationResponse.class,
                this::conversation);
    }

    @Override
    public CompletableFuture<List<Conversation>> listConversations(ListConversations request) {
        var path = request.getUserId().isPresent()
                ? "/conversations?limit={limit}&userId={userId}" : "/conversations?limit={limit}";
        Map<String, Object> variables = request.getUserId().isPresent()
                ? Map.of("limit", request.limit(), "userId", request.userId()) : Map.of("limit", request.limit());
        return get(
                "listConversations",
                path, variables,
                ConversationsResponse.class,
                response -> Objects.requireNonNull(
                                response.conversations(), "conversations array is required")
                        .stream()
                        .map(this::conversation)
                        .toList());
    }

    @Override
    public CompletableFuture<Conversation> getConversation(UUID conversationId) {
        return get(
                "getConversation",
                "/conversations/{conversationId}", Map.of("conversationId", conversationId),
                ConversationResponse.class,
                this::conversation);
    }

    @Override
    public CompletableFuture<List<Entity>> searchEntities(EntitySearch search) {
        var body = new SearchEntitiesRequest(search.query(), search.limit(), search.type());
        return post(
                "searchEntities",
                "/entities/search", Map.of(),
                body,
                EntitiesResponse.class,
                response -> nullToEmpty(response.entities()).stream()
                        .map(entity -> Objects.requireNonNull(entity, "entity"))
                        .toList());
    }

    @Override
    public CompletableFuture<Message> addMessage(UUID conversationId, NewMessage message) {
        return post(
                "addMessage",
                "/conversations/{conversationId}/messages", Map.of("conversationId", conversationId),
                message,
                Message.class,
                response -> response);
    }

    @Override
    public CompletableFuture<List<Message>> addMessages(
            UUID conversationId, List<NewMessage> messages) {
        var snapshot = List.copyOf(messages);
        return post(
                "addMessages",
                "/conversations/{conversationId}/messages/bulk", Map.of("conversationId", conversationId),
                new AddMessagesRequest(snapshot),
                MessagesResponse.class,
                response -> List.copyOf(nullToEmpty(response.messages())));
    }

    @Override
    public CompletableFuture<List<Message>> messages(UUID conversationId) {
        return readMessages("/conversations/{conversationId}/messages", Map.of("conversationId", conversationId));
    }

    @Override
    public CompletableFuture<List<Message>> messages(UUID conversationId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return readMessages("/conversations/{conversationId}/messages?limit={limit}",
                Map.of("conversationId", conversationId, "limit", limit));
    }

    private CompletableFuture<List<Message>> readMessages(String path, Map<String, Object> variables) {
        return get("messages", path, variables, MessagesResponse.class,
                response -> List.copyOf(Objects.requireNonNull(
                        response.messages(), "messages array is required")));
    }

    @Override
    public CompletableFuture<ConversationContext> context(UUID conversationId) {
        return get(
                "context",
                "/conversations/{conversationId}/context", Map.of("conversationId", conversationId),
                ConversationContext.class,
                response -> response);
    }

    @Override
    public CompletableFuture<ReasoningStep> recordStep(
            UUID conversationId, NewReasoningStep step) {
        var body = new RecordStepRequest(
                conversationId, step.reasoning(), step.actionTaken(), step.result());
        return post(
                "recordStep",
                "/reasoning/steps", Map.of(),
                body,
                RecordReasoningStepResponse.class,
                this::recordedReasoningStep);
    }

    @Override
    public CompletableFuture<ToolCall> recordToolCall(UUID stepId, NewToolCall call) {
        var body = new RecordToolCallRequest(
                stepId, call.toolName(), call.input(), call.status(), call.output(),
                call.duration() == null ? null : call.duration().toMillis());
        return post(
                "recordToolCall",
                "/reasoning/tool-calls", Map.of(),
                body,
                RecordToolCallResponse.class,
                response -> new ToolCall(
                        response.id(),
                        response.stepId(),
                        response.toolName(),
                        call.input(),
                        call.output(),
                        response.status(),
                        call.duration(),
                        null));
    }

    @Override
    public CompletableFuture<ReasoningTrace> trace(UUID conversationId) {
        return get(
                "trace",
                "/reasoning/trace/{conversationId}", Map.of("conversationId", conversationId),
                ReasoningTraceResponse.class,
                response -> reasoningTrace(conversationId, response));
    }

    @Override
    public CompletableFuture<ReasoningStepExplanation> explainReasoningStep(UUID stepId) {
        return get(
                "explainReasoningStep",
                "/reasoning/explain/{stepId}", Map.of("stepId", stepId),
                ReasoningStepExplanationResponse.class,
                this::reasoningStepExplanation);
    }

    @Override
    public CompletableFuture<Void> deleteConversation(UUID conversationId) {
        var operation = "deleteConversation";
        return logging.call(operation, log -> {
            var call = HttpCall.delete(uriTemplate("/conversations/{conversationId}"),
                    Map.of("conversationId", conversationId), headers());
            return exchange(operation, call, log).thenApply(result -> {
                requireSuccess(operation, result);
                return null;
            });
        });
    }

    private <W, T> CompletableFuture<T> get(
            String operation, String path, Map<String, Object> variables, Class<W> wireType, Function<W, T> transform) {
        return logging.call(operation, log -> {
            var call = HttpCall.get(uriTemplate(path), variables, headers());
            return exchangeAndDecode(operation, call, wireType, transform, log);
        });
    }

    private <W, T> CompletableFuture<T> post(
            String operation, String path, Map<String, Object> variables, Object wireRequest,
            Class<W> wireType, Function<W, T> transform) {
        return logging.call(operation, log -> {
            log.phase(ClientLogging.Phase.ENCODE);
            final byte[] body;
            try {
                body = jsonCodec.encode(wireRequest);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new MemoryClientException(
                        operation + " could not encode request JSON", failure));
            }
            log.phase(ClientLogging.Phase.REQUEST);
            var call = HttpCall.post(uriTemplate(path), variables, headers(), new HttpCall.ByteArrayBody(body));
            return exchangeAndDecode(operation, call, wireType, transform, log);
        });
    }

    private String uriTemplate(String path) {
        return endpoint.toString().replaceAll("/+$", "") + path;
    }

    private Map<String, String> headers() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        return headers;
    }

//    private HttpCall call(String method, String path, Map<String, Object> variables, byte[] body) {
//        return new HttpCall(method, endpoint.toString().replaceAll("/+$", "") + path,
//                variables, headers, body);
//    }

    private <W, T> CompletableFuture<T> exchangeAndDecode(
            String operation,
            HttpCall call,
            Class<W> wireType,
            Function<W, T> transform, ClientLogging.Operation log) {
        return exchange(operation, call, log).thenApply(result -> {
            requireSuccess(operation, result);
            log.phase(ClientLogging.Phase.DECODE);
            try {
                return transform.apply(jsonCodec.decode(result.body(), wireType));
            } catch (RuntimeException failure) {
                throw new ResponseDecodingException(
                        operation,
                        result.status(),
                        result.contentType(),
                        excerpt(result.body()),
                        failure);
            }
        });
    }

    private CompletableFuture<HttpResult> exchange(
            String operation, HttpCall call, ClientLogging.Operation log) {
        log.phase(ClientLogging.Phase.TRANSPORT);
        try {
            return transport.send(call).handle((result, failure) -> {
                if (failure != null) {
                    throw new MemoryClientException(
                            operation + " HTTP request failed", unwrap(failure));
                }
                log.response(result);
                return result;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new MemoryClientException(
                    operation + " HTTP request failed", failure));
        }
    }

    private static void requireSuccess(String operation, HttpResult result) {
        if (!result.isSuccess()) {
            throw new MemoryServiceException(
                    operation,
                    result.status(),
                    result.headers(),
                    result.contentType(),
                    excerpt(result.body()));
        }
    }

    private Conversation conversation(ConversationResponse response) {
        return new Conversation(this, response.id(), response.userId(), response.metadata(),
                instant(response.createdAt()), instant(response.updatedAt()), response.title(),
                response.firstMessageSnippet(), response.messageCount());
    }

    private ReasoningStep recordedReasoningStep(RecordReasoningStepResponse response) {
        return new ReasoningStep(
                this,
                response.id(),
                response.conversationId(),
                response.reasoning(),
                response.actionTaken(),
                response.result(),
                null);
    }

    private ReasoningTrace reasoningTrace(
            UUID requestedConversationId, ReasoningTraceResponse response) {
        var conversationId = response.conversationId() == null
                ? requestedConversationId
                : response.conversationId();
        var steps = nullToEmpty(response.steps()).stream()
                .map(step -> new ReasoningStep(
                        this,
                        step.id(),
                        conversationId,
                        step.reasoning(),
                        step.actionTaken(),
                        step.result(),
                        instant(step.createdAt())))
                .toList();
        var toolCalls = nullToEmpty(response.toolCalls()).stream()
                .map(this::toolCall)
                .toList();
        return new ReasoningTrace(conversationId, steps, toolCalls);
    }

    private ReasoningStepExplanation reasoningStepExplanation(
            ReasoningStepExplanationResponse response) {
        var step = new ReasoningStep(
                this,
                response.id(),
                response.conversationId(),
                response.reasoning(),
                response.actionTaken(),
                response.result(),
                instant(response.createdAt()));
        var calls = nullToEmpty(response.toolCalls()).stream()
                .map(this::toolCall)
                .toList();
        var entities = nullToEmpty(response.influencedEntities()).stream()
                .map(entity -> new Entity(
                        entity.id(), entity.name(), entity.type(), (String) null))
                .toList();
        return new ReasoningStepExplanation(step, calls, entities);
    }

    private ToolCall toolCall(ToolCallResponse response) {
        return new ToolCall(
                response.id(),
                response.stepId(),
                response.toolName(),
                response.input(),
                response.output(),
                response.status(),
                response.durationMs() == null
                        ? null
                        : Duration.ofMillis(response.durationMs()),
                instant(response.createdAt()));
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String excerpt(byte[] body) {
        var text = new String(body, UTF_8);
        return text.length() <= BODY_EXCERPT_LIMIT
                ? text
                : text.substring(0, BODY_EXCERPT_LIMIT) + "…";
    }

    private record CreateConversationRequest(String userId, Map<String, String> metadata) {}

    private record SearchEntitiesRequest(String query, int limit, String type) {}

    private record RecordStepRequest(
            UUID conversationId, String reasoning, String actionTaken, String result) {}

    private record RecordToolCallRequest(
            UUID stepId, String toolName, String input, ToolCallStatus status,
            String output, Long durationMs) {}

    private record ConversationResponse(
            UUID id, String userId, Map<String, String> metadata,
            String createdAt, String updatedAt, String title,
            String firstMessageSnippet, Long messageCount) {}

    private record ConversationsResponse(List<ConversationResponse> conversations) {}

    private record MessagesResponse(List<Message> messages) {}

    private record AddMessagesRequest(List<NewMessage> messages) {}

    private record EntitiesResponse(
            List<Entity> entities,
            String searchType) {}

    private record RecordReasoningStepResponse(
            UUID id,
            UUID conversationId,
            String reasoning,
            String actionTaken,
            String result) {}

    private record ReasoningStepResponse(
            UUID id,
            String reasoning,
            String actionTaken,
            String result,
            String createdAt) {}

    private record RecordToolCallResponse(
            UUID id,
            UUID stepId,
            String toolName,
            ToolCallStatus status) {}

    private record ToolCallResponse(
            UUID id,
            UUID stepId,
            String toolName,
            String input,
            String output,
            ToolCallStatus status,
            Long durationMs,
            String createdAt) {}

    private record ReasoningTraceResponse(
            UUID conversationId,
            List<ReasoningStepResponse> steps,
            List<ToolCallResponse> toolCalls) {}

    private record InfluencedEntityResponse(
            UUID id,
            String name,
            String type) {}

    private record ReasoningStepExplanationResponse(
            UUID id,
            UUID conversationId,
            String reasoning,
            String actionTaken,
            String result,
            String createdAt,
            List<ToolCallResponse> toolCalls,
            List<InfluencedEntityResponse> influencedEntities) {}
}
