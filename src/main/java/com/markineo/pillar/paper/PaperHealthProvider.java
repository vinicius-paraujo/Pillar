package com.markineo.pillar.paper;

import com.markineo.pillar.core.health.HealthProvider;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.redis.StreamConsumer;
import org.bukkit.Bukkit;

public final class PaperHealthProvider implements HealthProvider {

    private final StreamConsumer consumer;

    public PaperHealthProvider(StreamConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public HealthSnapshot current() {
        double mspt = averageMspt();
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int players = Bukkit.getOnlinePlayers().size();
        int worlds = Bukkit.getWorlds().size();
        int pendingSignals = consumer != null ? consumer.pendingSignals() : 0;

        return new HealthSnapshot(mspt, usedMemory, maxMemory, players, worlds, pendingSignals);
    }

    private double averageMspt() {
        long[] times = Bukkit.getServer().getTickTimes();
        if (times == null || times.length == 0) {
            return 0.0;
        }
        long sum = 0;
        for (long time : times) {
            sum += time;
        }
        return (sum / (double) times.length) / 1_000_000.0;
    }
}
