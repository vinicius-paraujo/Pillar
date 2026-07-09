package com.markineo.pillar.core.placement;

public record HardCaps(int maxPlayers, double maxMemoryPercent, double maxMspt) {
    public HardCaps {
        if (maxPlayers < 0) {
            throw new IllegalArgumentException("maxPlayers must be >= 0");
        }
        if (maxMemoryPercent < 0.0 || maxMemoryPercent > 1.0) {
            throw new IllegalArgumentException("maxMemoryPercent must be between 0.0 and 1.0");
        }
        if (maxMspt <= 0.0) {
            throw new IllegalArgumentException("maxMspt must be > 0");
        }
    }
}
