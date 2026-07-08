package com.markineo.pillar.core.task;

import com.markineo.pillar.logger.PillarLogger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class HandlerRegistry {
    private final CorrelationRegistry correlations;
    private final ConcurrentHashMap<String, MessageHandler> handlers = new ConcurrentHashMap<>();

    public HandlerRegistry(CorrelationRegistry correlations) {
        this.correlations = correlations;
    }

    public void register(MessageHandler handler) {
        handlers.put(handler.type().value(), handler);
    }

    // Returns the Consumer<Envelope> wired as the StreamConsumer sink.
    // Dispatch order: responses are completed first so the future resolves before
    // any secondary handler could also claim the envelope.
    public Consumer<Envelope> asSink(EnvelopeCodec codec, PillarLogger logger) {
        return envelope -> dispatch(envelope, codec, logger);
    }

    private void dispatch(Envelope envelope, EnvelopeCodec codec, PillarLogger logger) {
        // Try correlation first: if someone is waiting for this correlationId, complete the
        // future and stop. This works cross-node because UUIDs never collide. Self-send is
        // an edge: a node pinging itself would match its own pending correlationId here,
        // resolve the future with the ping (not a pong), and the handler would never run.
        // Accepted limitation for MVP; /pillar ping <self> should short-circuit earlier.
        if (couldBeAwaitedResponse(envelope) && correlations.complete(envelope.correlationId().get(), envelope)) {
            return;
        }

        MessageHandler handler = handlers.get(envelope.type().value());
        if (handler == null) {
            logger.warn("No handler registered for message type '" + envelope.type() + "'; discarding.");
            return;
        }

        handler.handle(envelope, codec);
    }

    // An envelope with a correlationId might be an awaited response, but it could also be
    // a request (e.g. ping). The actual check is correlations.complete() returning true,
    // meaning someone was actually waiting for this id.
    private boolean couldBeAwaitedResponse(Envelope envelope) {
        return envelope.correlationId().isPresent();
    }
}
