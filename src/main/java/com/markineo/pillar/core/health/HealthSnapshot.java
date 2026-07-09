package com.markineo.pillar.core.health;

public record HealthSnapshot(
        double mspt,
        long usedMemory,
        long maxMemory,
        int players,
        int worlds,
        int pendingSignals
) {
}
