package com.markineo.pillar.redis;

import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.CorrelationId;
import com.markineo.pillar.core.task.CorrelationRegistry;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.error.PillarException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class RequestSender {

    // Covers cross-server RTT (BLOCK_MILLIS = 2 s) plus Redis jitter. Callers may
    // pass a longer timeout for operations known to be slow.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final StreamPublisher publisher;
    private final CorrelationRegistry registry;

    public RequestSender(StreamPublisher publisher, CorrelationRegistry registry) {
        this.publisher = publisher;
        this.registry = registry;
    }

    public CompletableFuture<Envelope> send(ServerId destination, Envelope request) {
        return send(destination, request, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Envelope> send(ServerId destination, Envelope request, Duration timeout) {
        CorrelationId id = requireCorrelationId(request);
        CompletableFuture<Envelope> future = registry.register(id, timeout);
        try {
            publisher.publish(destination, request);
        } catch (PillarException e) {
            // The response can never arrive; fail immediately rather than waiting for the timeout.
            registry.fail(id, e);
        }
        return future;
    }

    private CorrelationId requireCorrelationId(Envelope request) {
        return request.correlationId().orElseThrow(() ->
                new PillarException("Cannot track a one-way envelope; use Envelope.request(...)."));
    }
}
