package com.markineo.pillar.core.task;

import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.error.PillarException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationRegistryTest {

    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);

    private ScheduledExecutorService scheduler;
    private CorrelationRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        registry = new CorrelationRegistry(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static Envelope response(CorrelationId id) {
        return Envelope.response(new MessageType("pillar.pong"), id, new ServerId("skyblock-1"), "{}");
    }

    @Test
    void completeResolvesTheRegisteredFuture() throws Exception {
        CorrelationId id = CorrelationId.generate();
        CompletableFuture<Envelope> future = registry.register(id, LONG_TIMEOUT);

        assertTrue(registry.complete(id, response(id)));
        assertEquals("pillar.pong", future.get(1, TimeUnit.SECONDS).type().value());
    }

    @Test
    void completeIsFalseForUnknownId() {
        assertFalse(registry.complete(CorrelationId.generate(), response(CorrelationId.generate())));
    }

    @Test
    void timeoutCompletesFutureExceptionally() {
        CorrelationId id = CorrelationId.generate();
        CompletableFuture<Envelope> future = registry.register(id, Duration.ofMillis(50));

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(PillarException.class, thrown.getCause());
    }

    @Test
    void completeAfterTimeoutIsRejected() throws Exception {
        CorrelationId id = CorrelationId.generate();
        CompletableFuture<Envelope> future = registry.register(id, Duration.ofMillis(50));

        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        // The expire already removed the entry; a late response finds nothing to complete.
        assertFalse(registry.complete(id, response(id)));
    }

    @Test
    void failResolvesExceptionallyAndConsumesTheId() {
        CorrelationId id = CorrelationId.generate();
        CompletableFuture<Envelope> future = registry.register(id, LONG_TIMEOUT);

        assertTrue(registry.fail(id, new PillarException("publish failed")));
        assertTrue(future.isCompletedExceptionally());
        assertFalse(registry.complete(id, response(id)));
    }

    @Test
    void closeFailsAllPendingFutures() {
        CompletableFuture<Envelope> first = registry.register(CorrelationId.generate(), LONG_TIMEOUT);
        CompletableFuture<Envelope> second = registry.register(CorrelationId.generate(), LONG_TIMEOUT);

        registry.close();

        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
    }
}
