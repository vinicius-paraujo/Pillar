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
        if (isResponse(envelope) && correlations.complete(envelope.correlationId().get(), envelope)) {
            return;
        }
        MessageHandler handler = handlers.get(envelope.type().value());
        if (handler == null) {
            logger.warn("No handler registered for message type '" + envelope.type() + "'; discarding.");
            return;
        }
        handler.handle(envelope, codec);
    }

    private boolean isResponse(Envelope envelope) {
        return envelope.correlationId().isPresent();
    }
}
