package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;

public final class EligibilityFilter {

    private final HardCaps caps;

    public EligibilityFilter(HardCaps caps) {
        this.caps = caps;
    }

    public boolean isEligible(HealthSnapshot snapshot) {
        if (snapshot.players() >= caps.maxPlayers()) {
            return false;
        }

        double memoryPercent = snapshot.maxMemory() > 0
                ? (double) snapshot.usedMemory() / snapshot.maxMemory()
                : 0.0;

        if (memoryPercent >= caps.maxMemoryPercent()) {
            return false;
        }

        return true;
    }
}
