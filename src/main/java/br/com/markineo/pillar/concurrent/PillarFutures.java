package br.com.markineo.pillar.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class PillarFutures {

    private PillarFutures() {
        // static utility class
    }

    public static <T> CompletableFuture<T> supplyGuarded(Supplier<T> supplier, ExecutorService pool, PlatformScheduler scheduler) {
        GuardedFuture<T> future = new GuardedFuture<>(scheduler);
        CompletableFuture.supplyAsync(supplier, pool).whenComplete((res, ex) -> {
            if (ex != null) {
                future.completeExceptionally(ex);
            } else {
                future.complete(res);
            }
        });
        return future;
    }

    public static <T> CompletableFuture<T> create(PlatformScheduler scheduler) {
        return new GuardedFuture<>(scheduler);
    }

    public static CompletableFuture<Void> runGuarded(Runnable runnable, ExecutorService pool, PlatformScheduler scheduler) {
        GuardedFuture<Void> future = new GuardedFuture<>(scheduler);
        CompletableFuture.runAsync(runnable, pool).whenComplete((res, ex) -> {
            if (ex != null) {
                future.completeExceptionally(ex);
            } else {
                future.complete(res);
            }
        });
        return future;
    }

    private static class GuardedFuture<T> extends CompletableFuture<T> {
        private final PlatformScheduler scheduler;

        public GuardedFuture(PlatformScheduler scheduler) {
            this.scheduler = scheduler;
        }

        private void checkThread() {
            if (scheduler.isMainThread()) {
                throw new IllegalStateException("Blocking I/O operation (join/get) on the main thread is forbidden.");
            }
        }

        @Override
        public T join() {
            checkThread();
            return super.join();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            checkThread();
            return super.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            checkThread();
            return super.get(timeout, unit);
        }
    }
}
