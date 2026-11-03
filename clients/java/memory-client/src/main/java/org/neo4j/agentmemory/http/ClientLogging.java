package org.neo4j.agentmemory.http;

import org.neo4j.agentmemory.conversation.ConversationContext;
import org.neo4j.agentmemory.reasoning.ReasoningStepExplanation;
import org.neo4j.agentmemory.reasoning.ReasoningTrace;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

final class ClientLogging {
    enum Phase { ENCODE, REQUEST, TRANSPORT, HTTP, DECODE }

    private static final AtomicLong NEXT_CALL_ID = new AtomicLong();
    private final System.Logger logger;

    ClientLogging() {
        this(System.getLogger("org.neo4j.agentmemory.MemoryClient"));
    }

    ClientLogging(System.Logger logger) { this.logger = logger; }

    void initialized(URI baseUrl, String transport, String json) {
        emit(System.Logger.Level.INFO, () -> "event=client.initialized transport=" + transport + " json=" + json + " baseUrl=" + baseUrl);
    }

    <T> CompletableFuture<T> call(String operation, Function<Operation, CompletableFuture<T>> action) {
        var log = new Operation(operation);
        emit(System.Logger.Level.DEBUG, () -> "event=operation.started operation="
                + log.operation + " callId=" + log.callId);
        try {
            var future = action.apply(log);
            future.whenComplete(log::finish);
            return future;
        } catch (RuntimeException failure) {
            log.finish(null, failure);
            throw failure;
        }
    }

    private void emit(System.Logger.Level level, Supplier<String> message) {
        try {
            if (logger.isLoggable(level)) logger.log(level, message);
        } catch (RuntimeException ignored) {
            // Logging has no effect on the operation's result.
        }
    }

    final class Operation {
        private final String operation;
        private final long callId = NEXT_CALL_ID.incrementAndGet();
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Phase phase = Phase.REQUEST;
        private volatile ResponseInfo response = new ResponseInfo(null, null);

        private Operation(String operation) { this.operation = operation; }

        void phase(Phase value) { phase = value; }

        void response(HttpResult result) {
            var raw = result.firstHeader("x-request-id")
                    .or(() -> result.firstHeader("request-id"))
                    .or(() -> result.firstHeader("x-amzn-requestid"));
            var requestId = raw.filter(value -> value.matches("[A-Za-z0-9._:-]{1,128}"))
                    .orElse(null);
            response = new ResponseInfo(result.status(), requestId);
            phase = Phase.HTTP;
        }

        private void finish(Object value, Throwable failure) {
            if (!terminal.compareAndSet(false, true)) return;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            var metadata = response;
            var lastPhase = phase;
            emit(System.Logger.Level.DEBUG, () -> {
                var cause = failure;
                while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                        && cause.getCause() != null) cause = cause.getCause();
                var outcome = cause == null ? "success"
                        : cause instanceof CancellationException ? "cancelled" : "failure";
                var text = "event=operation.completed operation=" + operation
                        + " callId=" + callId + " outcome=" + outcome
                        + " durationMs=" + Math.max(0, durationMs);
                if (metadata.status() != null) text += " status=" + metadata.status();
                if (metadata.requestId() != null) text += " requestId=" + metadata.requestId();
                if (cause != null) {
                    return text + " phase=" + lastPhase.name().toLowerCase(Locale.ROOT)
                            + " errorType=" + cause.getClass().getSimpleName();
                }
                return text + counts(value);
            });
        }
    }

    private record ResponseInfo(Integer status, String requestId) {}

    private static String counts(Object value) {
        if (value instanceof List<?> list) return " resultCount=" + list.size();
        if (value instanceof ConversationContext context) {
            return " reflectionCount=" + context.reflections().size()
                    + " observationCount=" + context.observations().size()
                    + " messageCount=" + context.recentMessages().size();
        }
        if (value instanceof ReasoningTrace trace) {
            return " stepCount=" + trace.steps().size() + " toolCallCount=" + trace.toolCalls().size();
        }
        if (value instanceof ReasoningStepExplanation explanation) {
            return " toolCallCount=" + explanation.toolCalls().size()
                    + " entityCount=" + explanation.influencedEntities().size();
        }
        return "";
    }
}
