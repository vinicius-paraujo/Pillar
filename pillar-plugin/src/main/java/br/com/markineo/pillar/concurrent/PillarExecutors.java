package br.com.markineo.pillar.concurrent;

import br.com.markineo.pillar.logger.PillarLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public final class PillarExecutors {

    private final PillarLogger logger;

    public PillarExecutors(PillarLogger logger) {
        this.logger = logger;
    }

    public ExecutorService newSingleThread(String name) {
        return Executors.newSingleThreadExecutor(threadFactory(name));
    }

    public ExecutorService newFixedPool(String name, int size) {
        return Executors.newFixedThreadPool(size, threadFactory(name));
    }

    /**
     * A bounded pool whose rejection handler blocks the submitting thread until the queue
     * drains, applying backpressure instead of dropping tasks. Because a full queue stalls
     * the caller, only Pillar-owned worker threads may submit here — never the Paper main
     * thread or Velocity event loop, which this would freeze.
     */
    public ThreadPoolExecutor newBoundedWorkerPool(String name, int poolSize, int queueCapacity) {
        return new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory(name),
                (r, executor) -> {
                    try {
                        executor.getQueue().put(r);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
        );
    }

    public ScheduledExecutorService newSingleThreadScheduled(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory(name));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private ThreadFactory threadFactory(String name) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "pillar-" + name + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) ->
                    logger.error("Uncaught exception in thread " + t.getName(), error));
            return thread;
        };
    }
}
