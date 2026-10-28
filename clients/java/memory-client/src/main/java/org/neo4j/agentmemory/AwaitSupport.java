package org.neo4j.agentmemory;

import org.neo4j.agentmemory.exception.MemoryClientException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

final class AwaitSupport {
    private static final String ENV = "NAMS_AWAIT_TIMEOUT_SECONDS";
    private static final Duration FALLBACK = Duration.ofSeconds(30);
    private static final System.Logger LOGGER = System.getLogger(AwaitSupport.class.getName());

    private AwaitSupport() {}

    static <T> T await(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "future must not be null");
        return await(future, DefaultTimeout.VALUE);
    }

    static <T> T await(CompletableFuture<T> future, Duration timeout) {
        Objects.requireNonNull(future, "future must not be null");
        long nanos = positiveNanos(timeout);
        try {
            return future.get(nanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MemoryClientException("Interrupted while awaiting completion", interrupted);
        } catch (TimeoutException timedOut) {
            throw new MemoryClientException("Await timed out after " + timeout, timedOut);
        } catch (ExecutionException failure) {
            throw mapped(failure);
        }
    }

    private static RuntimeException mapped(Throwable failure) {
        while ((failure instanceof ExecutionException || failure instanceof CompletionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new MemoryClientException("Asynchronous operation failed", failure);
    }

    private static long positiveNanos(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be a positive Duration");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout exceeds the supported nanosecond range", overflow);
        }
    }

    static Duration resolveDefaultTimeout(String raw, System.Logger logger) {
        if (raw == null || raw.isBlank()) {
            logger.log(System.Logger.Level.INFO,
                    ENV + " is unset or blank; using default await timeout of 30 seconds.");
            return FALLBACK;
        }
        try {
            var timeout = Duration.ofSeconds(Long.parseLong(raw.trim()));
            positiveNanos(timeout);
            return timeout;
        } catch (IllegalArgumentException invalid) {
            logger.log(System.Logger.Level.WARNING,
                    ENV + " is invalid; expected positive whole seconds within the supported range; "
                            + "using default await timeout of 30 seconds.");
            return FALLBACK;
        }
    }

    private static final class DefaultTimeout {
        private static final Duration VALUE = resolveDefaultTimeout(System.getenv(ENV), LOGGER);
    }
}
