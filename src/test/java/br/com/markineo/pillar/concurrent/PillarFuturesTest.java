package br.com.markineo.pillar.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void testGuardedJoinThrowsOnMainThread() {
        PlatformScheduler mainThreadScheduler = new PlatformScheduler() {
            @Override
            public void runSync(Runnable task) {
                task.run();
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        };

        CompletableFuture<String> future = PillarFutures.supplyGuarded(() -> "Hello", pool, mainThreadScheduler);

        IllegalStateException ex = assertThrows(IllegalStateException.class, future::join);
        assertTrue(ex.getMessage().contains("main thread is forbidden"));
    }

    @Test
    void testGuardedJoinSucceedsOnWorkerThread() {
        PlatformScheduler workerThreadScheduler = new PlatformScheduler() {
            @Override
            public void runSync(Runnable task) {
                task.run();
            }

            @Override
            public boolean isMainThread() {
                return false;
            }
        };

        CompletableFuture<String> future = PillarFutures.supplyGuarded(() -> "Hello", pool, workerThreadScheduler);

        assertEquals("Hello", future.join());
    }

    @Test
    void testGuardedGetThrowsOnMainThread() {
        PlatformScheduler mainThreadScheduler = new PlatformScheduler() {
            @Override
            public void runSync(Runnable task) {
                task.run();
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        };

        CompletableFuture<Void> future = PillarFutures.runGuarded(() -> {}, pool, mainThreadScheduler);

        IllegalStateException ex = assertThrows(IllegalStateException.class, future::get);
        assertTrue(ex.getMessage().contains("main thread is forbidden"));
    }
}
