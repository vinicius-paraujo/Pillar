package com.markineo.pillar.core.task;

import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.logger.PillarLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerRegistryTest {

    // Dispatch forwards the codec to handlers but these tests never decode a payload.
    private static final EnvelopeCodec UNUSED_CODEC = new EnvelopeCodec() {
        @Override
        public String encode(Envelope envelope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Envelope decode(String wire) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decodePayload(Envelope envelope, Class<T> type) {
            throw new UnsupportedOperationException();
        }
    };

    private ScheduledExecutorService scheduler;
    private CorrelationRegistry correlations;
    private HandlerRegistry handlers;
    private PillarLogger logger;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        correlations = new CorrelationRegistry(scheduler);
        handlers = new HandlerRegistry(correlations);
        logger = new PillarLogger(LoggerFactory.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void requestNobodyAwaitsReachesItsHandler() {
        RecordingHandler ping = new RecordingHandler(PillarMessageTypes.PING);
        handlers.register(ping);
        Consumer<Envelope> sink = handlers.asSink(UNUSED_CODEC, logger);

        sink.accept(Envelope.request(PillarMessageTypes.PING, new ServerId("alpha"), "{}"));

        assertEquals(1, ping.calls.get());
    }

    // Locks the known self-send limitation so a future fix in Iteration 2 is a deliberate
    // change: a node that pings itself registers a correlation id, then reads its own request
    // back. dispatch() matches that pending id and resolves the future with the request (a
    // ping, not a pong), and the handler never runs. The command layer short-circuits
    // ping <self> so this cannot happen through the UI today, but the dispatch bug remains.
    @Test
    void selfSentRequestIsMisroutedToItsOwnPendingFuture() {
        RecordingHandler ping = new RecordingHandler(PillarMessageTypes.PING);
        handlers.register(ping);
        Consumer<Envelope> sink = handlers.asSink(UNUSED_CODEC, logger);

        Envelope selfPing = Envelope.request(PillarMessageTypes.PING, new ServerId("alpha"), "{}");
        CorrelationId id = selfPing.correlationId().orElseThrow();
        CompletableFuture<Envelope> awaiting = correlations.register(id, Duration.ofSeconds(30));

        sink.accept(selfPing);

        assertTrue(awaiting.isDone());
        assertEquals(PillarMessageTypes.PING, awaiting.getNow(null).type());
        assertEquals(0, ping.calls.get());
    }

    private static final class RecordingHandler implements MessageHandler {
        private final MessageType type;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingHandler(MessageType type) {
            this.type = type;
        }

        @Override
        public MessageType type() {
            return type;
        }

        @Override
        public void handle(Envelope envelope, EnvelopeCodec codec) {
            calls.incrementAndGet();
        }
    }
}
