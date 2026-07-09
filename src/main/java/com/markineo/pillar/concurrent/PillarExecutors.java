package com.markineo.pillar.concurrent;

import com.markineo.pillar.logger.PillarLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        return Executors.newSingleThreadScheduledExecutor(threadFactory(name));
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
