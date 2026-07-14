package br.com.markineo.pillar.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PillarFuturesTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private PlatformScheduler scheduler(boolean mainThread) {
        return new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return mainThread; }
        };
    }

    @Test
    void testGuardedJoinThrowsOnMainThread() {
        CompletableFuture<String> future = PillarFutures.supplyGuarded(() -> "Hello", pool, scheduler(true));

        IllegalStateException ex = assertThrows(IllegalStateException.class, future::join);
        assertTrue(ex.getMessage().contains("main thread"));
    }

    @Test
    void testGuardedJoinSucceedsOnWorkerThread() {
        CompletableFuture<String> future = PillarFutures.supplyGuarded(() -> "Hello", pool, scheduler(false));

        assertEquals("Hello", future.join());
    }

    @Test
    void testGuardedGetThrowsOnMainThread() {
        CompletableFuture<Void> future = PillarFutures.runGuarded(() -> {}, pool, scheduler(true));

        IllegalStateException ex = assertThrows(IllegalStateException.class, future::get);
        assertTrue(ex.getMessage().contains("main thread"));
    }

    @Test
    void testGuardPropagatesToChainedStages() {
        // A derived stage must inherit the guard; otherwise join() on a chained future
        // silently blocks the main thread — the exact hole newIncompleteFuture() closes.
        CompletableFuture<String> chained =
                PillarFutures.supplyGuarded(() -> "Hello", pool, scheduler(true)).thenApply(s -> s + " World");

        IllegalStateException ex = assertThrows(IllegalStateException.class, chained::join);
        assertTrue(ex.getMessage().contains("main thread"));
    }
}
