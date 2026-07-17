package br.com.markineo.pillar.paper;

import br.com.markineo.pillar.core.health.HealthProvider;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.health.SignalTracker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class PaperHealthProvider implements HealthProvider {

    private final SignalTracker tracker;
    private volatile double cachedMspt;
    private volatile int cachedPlayers;
    private volatile int cachedWorlds;

    public PaperHealthProvider(Plugin plugin, SignalTracker tracker) {
        this.tracker = tracker;
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateCache, 20L, 20L);
    }

    private void updateCache() {
        this.cachedMspt = averageMspt();
        this.cachedPlayers = Bukkit.getOnlinePlayers().size();
        this.cachedWorlds = Bukkit.getWorlds().size();
    }

    @Override
    public HealthSnapshot current() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int pendingSignals = tracker != null ? tracker.pendingSignals() : 0;

        return new HealthSnapshot(cachedMspt, usedMemory, maxMemory, cachedPlayers, cachedWorlds, pendingSignals);
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
