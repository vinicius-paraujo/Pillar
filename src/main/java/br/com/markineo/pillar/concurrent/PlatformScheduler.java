package br.com.markineo.pillar.concurrent;

public interface PlatformScheduler {
    void runSync(Runnable task);
    boolean isMainThread();
}
