package org.neo4j.agentmemory.http;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

final class RecordingClientLogger implements System.Logger {
    record Entry(Level level, String message, Throwable thrown) {
        Entry(Level level, String message) { this(level, message, null); }
    }

    private final List<Entry> captured = new ArrayList<>();
    private final Level threshold;

    RecordingClientLogger(Level threshold) { this.threshold = threshold; }

    public String getName() { return "recording-client-logger"; }

    public boolean isLoggable(Level level) {
        return level.getSeverity() >= threshold.getSeverity();
    }

    private synchronized void append(Level level, String message, Throwable thrown) {
        if (isLoggable(level)) {
            captured.add(new Entry(level, message, thrown));
            notifyAll();
        }
    }

    synchronized List<Entry> entries() { return List.copyOf(captured); }

    synchronized List<Entry> awaitEvents(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (captured.size() < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new AssertionError("Missing log events: " + captured);
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return List.copyOf(captured);
    }

    public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
        append(level, message, thrown);
    }

    public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
        append(level, MessageFormat.format(format, parameters), null);
    }
}
