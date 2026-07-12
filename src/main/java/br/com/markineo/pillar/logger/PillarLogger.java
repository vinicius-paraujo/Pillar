package br.com.markineo.pillar.logger;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class PillarLogger {
    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    public record Entry(
            Instant time,
            Level level,
            String message
    ) {}

    private static final int HISTORY_LIMIT = 100;

    private final Logger delegate;
    // Retained so /pillar diagnostics can show recent activity without reading a log file.
    private final Deque<Entry> history = new ArrayDeque<>(HISTORY_LIMIT);

    public PillarLogger(Logger delegate) {
        this.delegate = delegate;
    }

    public void debug(String message) {
        delegate.debug(message);
        record(Level.DEBUG, message);
    }

    public void info(String message) {
        delegate.info(message);
        record(Level.INFO, message);
    }

    public void warn(String message) {
        delegate.warn(message);
        record(Level.WARN, message);
    }

    public void error(String message) {
        delegate.error(message);
        record(Level.ERROR, message);
    }

    public void error(String message, Throwable cause) {
        delegate.error(message, cause);
        record(Level.ERROR, message);
    }

    public List<Entry> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    private void record(Level level, String message) {
        synchronized (history) {
            if (history.size() == HISTORY_LIMIT) {
                history.removeFirst();
            }
            history.addLast(new Entry(Instant.now(), level, message));
        }
    }
}
