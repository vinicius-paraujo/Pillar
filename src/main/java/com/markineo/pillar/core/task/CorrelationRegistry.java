package com.markineo.pillar.core.task;

import com.markineo.pillar.error.PillarException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Does not own the scheduler: the composition root names and closes every executor.
public final class CorrelationRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<Envelope>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public CorrelationRegistry(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public CompletableFuture<Envelope> register(CorrelationId id, Duration timeout) {
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        pending.put(id.value(), future);
        scheduler.schedule(() -> expire(id), timeout.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    // Returns true if the id was known and the future was completed; false if it had
    // already been expired or cancelled (caller can log and discard the stale response).
    public boolean complete(CorrelationId id, Envelope response) {
        CompletableFuture<Envelope> future = pending.remove(id.value());
        if (future == null) {
            return false;
        }
        future.complete(response);
        return true;
    }

    // Used when a publish failure makes the response permanently unreachable, so the
    // caller is not left waiting until the timeout fires.
    public boolean fail(CorrelationId id, Throwable cause) {
        CompletableFuture<Envelope> future = pending.remove(id.value());
        if (future == null) {
            return false;
        }
        future.completeExceptionally(cause);
        return true;
    }

    // Callers waiting on futures must not be left blocked when the node shuts down.
    public void close() {
        pending.forEach((key, future) ->
                future.completeExceptionally(
                        new PillarException("Request cancelled: node is shutting down.")));
        pending.clear();
    }

    private void expire(CorrelationId id) {
        CompletableFuture<Envelope> future = pending.remove(id.value());
        if (future != null) {
            future.completeExceptionally(new PillarException("Request timed out: " + id.value()));
        }
    }
}
