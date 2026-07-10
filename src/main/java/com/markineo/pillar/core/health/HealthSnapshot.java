package com.markineo.pillar.core.health;

public record HealthSnapshot(
        double mspt,
        long usedMemory,
        long maxMemory,
        int players,
        int worlds,
        int pendingSignals
) {
    public HealthSnapshot {
        if (Double.isNaN(mspt) || Double.isInfinite(mspt) || mspt < 0) {
            throw new IllegalArgumentException("mspt must be a finite non-negative value, got " + mspt);
        }
        if (usedMemory < 0) {
            throw new IllegalArgumentException("usedMemory must be non-negative, got " + usedMemory);
        }
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be positive, got " + maxMemory);
        }
        if (players < 0) {
            throw new IllegalArgumentException("players must be non-negative, got " + players);
        }
        if (worlds < 0) {
            throw new IllegalArgumentException("worlds must be non-negative, got " + worlds);
        }
        if (pendingSignals < 0) {
            throw new IllegalArgumentException("pendingSignals must be non-negative, got " + pendingSignals);
        }
    }
}
