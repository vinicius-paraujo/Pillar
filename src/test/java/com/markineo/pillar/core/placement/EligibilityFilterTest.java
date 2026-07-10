package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EligibilityFilterTest {

    @Test
    void rejectsInvalidCaps() {
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(-1, 0.5, 45.0));
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(100, -0.1, 45.0));
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(100, 1.1, 45.0));
        assertThrows(IllegalArgumentException.class, () -> new HardCaps(100, 0.8, -1.0));
    }

    @Test
    void eligibleWhenBelowCaps() {
        HardCaps caps = new HardCaps(100, 0.8, 45.0);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 50, 100, 50, 2, 0);
        assertTrue(filter.isEligible(snapshot), "Should be eligible when memory (50%), players (50), and mspt (20.0) are below caps");
    }

    @Test
    void notEligibleWhenExceedingPlayerCap() {
        HardCaps caps = new HardCaps(100, 0.8, 45.0);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 50, 100, 100, 2, 0);
        assertFalse(filter.isEligible(snapshot), "Should not be eligible when players match cap exactly");

        HealthSnapshot overSnapshot = new HealthSnapshot(20.0, 50, 100, 101, 2, 0);
        assertFalse(filter.isEligible(overSnapshot), "Should not be eligible when players exceed cap");
    }

    @Test
    void notEligibleWhenPlayersPlusPendingSignalsExceedsCap() {
        HardCaps caps = new HardCaps(100, 0.8, 45.0);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 50, 100, 95, 2, 5);
        assertFalse(filter.isEligible(snapshot), "Should not be eligible when players (95) + pending signals (5) matches cap (100)");

        HealthSnapshot overSnapshot = new HealthSnapshot(20.0, 50, 100, 95, 2, 6);
        assertFalse(filter.isEligible(overSnapshot), "Should not be eligible when players (95) + pending signals (6) exceeds cap (100)");
    }

    @Test
    void notEligibleWhenExceedingMemoryCap() {
        HardCaps caps = new HardCaps(100, 0.8, 45.0);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(20.0, 80, 100, 50, 2, 0);
        assertFalse(filter.isEligible(snapshot), "Should not be eligible when memory matches cap exactly (80%)");

        HealthSnapshot overSnapshot = new HealthSnapshot(20.0, 81, 100, 50, 2, 0);
        assertFalse(filter.isEligible(overSnapshot), "Should not be eligible when memory exceeds cap");
    }

    @Test
    void notEligibleWhenExceedingMsptCap() {
        HardCaps caps = new HardCaps(100, 0.8, 45.0);
        EligibilityFilter filter = new EligibilityFilter(caps);

        HealthSnapshot snapshot = new HealthSnapshot(45.0, 50, 100, 50, 2, 0);
        assertFalse(filter.isEligible(snapshot), "Should not be eligible when MSPT matches cap exactly (45.0)");

        HealthSnapshot overSnapshot = new HealthSnapshot(50.0, 50, 100, 50, 2, 0);
        assertFalse(filter.isEligible(overSnapshot), "Should not be eligible when MSPT exceeds cap (50.0)");
    }

    @Test
    void rejectsZeroMaxMemoryAtConstructionTime() {
        assertThrows(IllegalArgumentException.class, () -> new HealthSnapshot(20.0, 0, 0, 50, 2, 0),
                "maxMemory <= 0 should be rejected at the trust boundary");
    }
}
