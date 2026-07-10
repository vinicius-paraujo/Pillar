package com.markineo.pillar.core.task;

import com.markineo.pillar.error.PillarException;
import com.markineo.pillar.error.TimeoutPillarException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// Does not own the scheduler: the composition root names and closes every executor.
public final class CorrelationRegistry {

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public CorrelationRegistry(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public CompletableFuture<Envelope> register(CorrelationId id, Duration timeout) {
        Pending entry = new Pending(new CompletableFuture<>());
        // Registered before scheduling so a same-tick expiry always finds the entry to remove;
        // if it fired first, the later assignment lands on an already-removed entry harmlessly.
        pending.put(id.value(), entry);
        entry.timeout = scheduler.schedule(() -> expire(id), timeout.toMillis(), TimeUnit.MILLISECONDS);
        return entry.future;
    }

    // Returns true if the id was known and the future was completed; false if it had
    // already been expired or cancelled (caller can log and discard the stale response).
    public boolean complete(CorrelationId id, Envelope response) {
        Pending entry = pending.remove(id.value());
        if (entry == null) {
            return false;
        }
        cancelTimeout(entry);
        entry.future.complete(response);
        return true;
    }

    // Used when a publish failure makes the response permanently unreachable, so the
    // caller is not left waiting until the timeout fires.
    public boolean fail(CorrelationId id, Throwable cause) {
        Pending entry = pending.remove(id.value());
        if (entry == null) {
            return false;
        }
        cancelTimeout(entry);
        entry.future.completeExceptionally(cause);
        return true;
    }

    // Callers waiting on futures must not be left blocked when the node shuts down.
    public void close() {
        pending.forEach((key, entry) -> {
            cancelTimeout(entry);
            entry.future.completeExceptionally(
                    new PillarException("Request cancelled: node is shutting down."));
        });
        pending.clear();
    }

    private void expire(CorrelationId id) {
        Pending entry = pending.remove(id.value());
        if (entry != null) {
            entry.future.completeExceptionally(new TimeoutPillarException("Request timed out: " + id.value()));
        }
    }

    // Cancelling on resolution keeps the scheduler's queue proportional to in-flight requests,
    // not to total throughput; a resolved request leaves no dead expiry task behind.
    private void cancelTimeout(Pending entry) {
        ScheduledFuture<?> timeout = entry.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private static final class Pending {
        private final CompletableFuture<Envelope> future;
        private volatile ScheduledFuture<?> timeout;

        private Pending(CompletableFuture<Envelope> future) {
            this.future = future;
        }
    }
}
