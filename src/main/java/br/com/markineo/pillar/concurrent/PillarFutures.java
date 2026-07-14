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

        // Derived stages (thenApply, thenAccept, whenComplete, ...) are built through this
        // factory; overriding it makes them GuardedFuture too, so the anti-join guard survives
        // a chain instead of protecting only the future handed back from supplyGuarded.
        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new GuardedFuture<>(scheduler);
        }

        private void checkThread(String op) {
            if (scheduler.isMainThread()) {
                throw new IllegalStateException("Blocking the main thread on Redis I/O is forbidden (it freezes the server tick). Consume the result asynchronously using thenApply/thenAccept, and hop back to the main thread via the scheduler only when you need to touch game state.");
            }
        }

        @Override
        public T join() {
            checkThread("join");
            return super.join();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            checkThread("get");
            return super.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            checkThread("get");
            return super.get(timeout, unit);
        }
    }
}
