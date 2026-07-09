package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EligibilityFilterTest {

    @Test
    void rejectsInvalidCaps() {
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(-1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(100, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(100, 1.1));
    }

    @Test
    void eligibleWhenBelowCaps() {
        HardCaps caps = new HardCaps(100, 0.8);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 50, 100, 50, 2, 0);
        assertTrue(filter.isEligible(snapshot), "Should be eligible when memory (50%) and players (50) are below caps");
    }

    @Test
    void notEligibleWhenExceedingPlayerCap() {
        HardCaps caps = new HardCaps(100, 0.8);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 50, 100, 100, 2, 0);
        assertFalse(filter.isEligible(snapshot), "Should not be eligible when players match cap exactly");

        HealthSnapshot overSnapshot = new HealthSnapshot(20.0, 50, 100, 101, 2, 0);
        assertFalse(filter.isEligible(overSnapshot), "Should not be eligible when players exceed cap");
    }

    @Test
    void notEligibleWhenExceedingMemoryCap() {
        HardCaps caps = new HardCaps(100, 0.8);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 80, 100, 50, 2, 0);
        assertFalse(filter.isEligible(snapshot), "Should not be eligible when memory matches cap exactly (80%)");

        HealthSnapshot overSnapshot = new HealthSnapshot(20.0, 81, 100, 50, 2, 0);
        assertFalse(filter.isEligible(overSnapshot), "Should not be eligible when memory exceeds cap");
    }

    @Test
    void protectsAgainstDivisionByZeroWhenMaxMemoryIsZero() {
        HardCaps caps = new HardCaps(100, 0.8);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 0, 0, 50, 2, 0);
        assertTrue(filter.isEligible(snapshot), "Should treat 0 max memory as 0% usage, remaining eligible");
    }
}
