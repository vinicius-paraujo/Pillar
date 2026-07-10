package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;

public final class EligibilityFilter {

    private final HardCaps caps;

    public EligibilityFilter(HardCaps caps) {
        this.caps = caps;
    }

    public boolean isEligible(HealthSnapshot snapshot) {
        if (snapshot == null) {
            return true; // Delegates the blind node to PlacementSelector to apply the penalty
        }

        if ((snapshot.players() + snapshot.pendingSignals()) >= caps.maxPlayers()) {
            return false;
        }

        if (snapshot.mspt() >= caps.maxMspt()) {
            return false;
        }

        double memoryPercent = snapshot.maxMemory() > 0
                ? (double) snapshot.usedMemory() / snapshot.maxMemory()
                : 0.0;

        return memoryPercent < caps.maxMemoryPercent();
    }
}
