package com.markineo.pillar.velocity;

import com.markineo.pillar.core.health.HealthProvider;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.redis.StreamConsumer;
import com.velocitypowered.api.proxy.ProxyServer;

public final class VelocityHealthProvider implements HealthProvider {

    private final ProxyServer proxy;
    private final StreamConsumer consumer;

    public VelocityHealthProvider(ProxyServer proxy, StreamConsumer consumer) {
        this.proxy = proxy;
        this.consumer = consumer;
    }

    @Override
    public HealthSnapshot current() {
        double mspt = 0.0; // Proxy does not tick like a backend server
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int players = proxy.getPlayerCount();
        int worlds = 0; // Proxy does not have worlds
        int pendingSignals = consumer != null ? consumer.pendingSignals() : 0;

        return new HealthSnapshot(mspt, usedMemory, maxMemory, players, worlds, pendingSignals);
    }
}
