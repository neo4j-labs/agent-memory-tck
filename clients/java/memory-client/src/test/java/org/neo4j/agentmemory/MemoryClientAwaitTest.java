package org.neo4j.agentmemory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.exception.ResponseDecodingException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.MemoryClient.await;

class MemoryClientAwaitTest {
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void returnsTheOriginalValueAndSuccessfulNull() {
        var value = new Object();
        assertThat(MemoryClient.await(CompletableFuture.completedFuture(value), SECOND))
                .isSameAs(value);
        assertThat(await(CompletableFuture.<Void>completedFuture(null), SECOND))
                .isNull();
    }

    @Test
    void preservesClientDiagnosticsAndOtherUncheckedFailures() {
        var service = new MemoryServiceException("read", 503,
                Map.of("retry-after", List.of("1")), "application/json", "unavailable");
        var decode = new ResponseDecodingException("read", 200, "application/json",
                "invalid", new IOException("bad payload"));
        for (Throwable original : List.of(service, decode,
                new IllegalStateException("application"), new AssertionError("fatal"))) {
            var wrapped = new CompletionException(new ExecutionException(original));
            assertThatThrownBy(() -> await(CompletableFuture.failedFuture(wrapped), SECOND))
                    .isSameAs(original);
        }
    }

    @Test
    void wrapsCheckedFailuresWithoutInterruptingTheWaitingThread() {
        var failure = new InterruptedException("another computation was interrupted");
        assertThatThrownBy(() -> await(CompletableFuture.failedFuture(failure), SECOND))
                .isInstanceOf(MemoryClientException.class).hasCause(failure);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void preservesDirectAndDependentCancellation() {
        var source = new CompletableFuture<String>();
        var dependent = source.thenApply(String::length);
        source.cancel(false);
        assertThatThrownBy(() -> await(source, SECOND))
                .isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> await(dependent, SECOND))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void timedOutFutureCanStillCompleteNormally() {
        var source = new CompletableFuture<String>();
        assertThatThrownBy(() -> await(source, Duration.ofMillis(1)))
                .isInstanceOf(MemoryClientException.class)
                .hasCauseInstanceOf(TimeoutException.class)
                .hasMessageContaining("PT0.001S");
        assertThat(source.isDone()).isFalse();
        assertThat(source.complete("later")).isTrue();
        assertThat(await(source, SECOND)).isEqualTo("later");
    }

    @Test
    void interruptionRestoresFlagAndLeavesFutureUsable() {
        var source = new CompletableFuture<String>();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> await(source, SECOND))
                    .isInstanceOf(MemoryClientException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(source.isDone()).isFalse();
        } finally {
            Thread.interrupted();
        }
        assertThat(source.complete("later")).isTrue();
        assertThat(await(source, SECOND)).isEqualTo("later");
    }

    @Test
    void rejectsInvalidExplicitArgumentsEvenForCompletedFutures() {
        var source = CompletableFuture.completedFuture("ready");
        assertThatThrownBy(() -> await(source, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (var duration : List.of(Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> await(source, duration))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> await(null, SECOND))
                .isInstanceOf(NullPointerException.class);
        assertThat(await(source, Duration.ofNanos(1))).isEqualTo("ready");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentSettingLogsInfoAndUsesThirtySeconds(String raw) {
        var logger = new RecordingLogger();
        assertThat(AwaitSupport.resolveDefaultTimeout(raw, logger)).isEqualTo(Duration.ofSeconds(30));
        assertThat(logger.events).hasSize(1);
        assertThat(logger.events.get(0)).startsWith("INFO:")
                .contains("NAMS_AWAIT_TIMEOUT_SECONDS", "30 seconds");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "0", "-1", "1.5", "9223372037", "999999999999999999999"})
    void invalidSettingLogsWarningAndUsesThirtySeconds(String raw) {
        var logger = new RecordingLogger();
        assertThat(AwaitSupport.resolveDefaultTimeout(raw, logger)).isEqualTo(Duration.ofSeconds(30));
        assertThat(logger.events).hasSize(1);
        assertThat(logger.events.get(0)).startsWith("WARNING:")
                .contains("NAMS_AWAIT_TIMEOUT_SECONDS", "30 seconds");
    }

    @Test
    void acceptsTrimmedPositiveWholeSecondsWithoutFallbackLogging() {
        var logger = new RecordingLogger();
        assertThat(AwaitSupport.resolveDefaultTimeout(" 2 ", logger)).isEqualTo(Duration.ofSeconds(2));
        assertThat(AwaitSupport.resolveDefaultTimeout("9223372036", logger))
                .isEqualTo(Duration.ofSeconds(9223372036L));
        assertThat(logger.events).isEmpty();
    }

    @Test
    void defaultConfigurationLogsOnceAcrossConcurrentCalls() throws Exception {
        var missing = probe(null, "default");
        assertThat(missing.lines().filter(line -> line.contains("using default await timeout")))
                .hasSize(1);
        assertThat(missing).contains(" INFO ", "org.neo4j.agentmemory.AwaitSupport",
                "NAMS_AWAIT_TIMEOUT_SECONDS", "30 seconds");
        var invalid = probe("invalid", "default");
        assertThat(invalid.lines().filter(line -> line.contains("using default await timeout")))
                .hasSize(1);
        assertThat(invalid).contains(" WARN ", "org.neo4j.agentmemory.AwaitSupport", "30 seconds");
    }

    @Test
    void explicitDurationBypassesEnvironmentAndDefaultLogging() throws Exception {
        assertThat(probe("invalid", "explicit")).doesNotContain("NAMS_AWAIT_TIMEOUT_SECONDS");
        assertThat(probe(null, "explicit")).doesNotContain("NAMS_AWAIT_TIMEOUT_SECONDS");
    }

    @Test
    void environmentControlsTheActualDefaultWait() throws Exception {
        assertThat(probe("1", "pending")).contains("timed out").doesNotContain("using default");
    }

    private static String probe(String setting, String mode) throws Exception {
        var command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), mode).redirectErrorStream(true);
        if (setting == null) command.environment().remove("NAMS_AWAIT_TIMEOUT_SECONDS");
        else command.environment().put("NAMS_AWAIT_TIMEOUT_SECONDS", setting);
        var process = command.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            if (args[0].equals("pending")) {
                try {
                    await(new CompletableFuture<>());
                    throw new AssertionError("expected a timed wait");
                } catch (MemoryClientException failure) {
                    if (!(failure.getCause() instanceof TimeoutException)) throw failure;
                    System.out.println("timed out");
                }
                return;
            }
            IntStream.range(0, 16).parallel().forEach(index -> {
                var future = CompletableFuture.completedFuture("ready");
                var value = args[0].equals("explicit")
                        ? await(future, SECOND) : await(future);
                if (!value.equals("ready")) throw new AssertionError(value);
            });
        }
    }

    private static class RecordingLogger implements System.Logger {
        final List<String> events = new ArrayList<>();
        public String getName() { return "await-test"; }
        public boolean isLoggable(Level level) { return true; }
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            events.add(level + ":" + message);
        }
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            events.add(level + ":" + java.text.MessageFormat.format(format, params));
        }
    }
}
