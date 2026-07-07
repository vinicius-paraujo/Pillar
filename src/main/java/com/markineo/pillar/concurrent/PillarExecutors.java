package com.markineo.pillar.concurrent;

import com.markineo.pillar.logger.PillarLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
