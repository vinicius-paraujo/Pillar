package com.markineo.pillar.core.task;

import java.util.UUID;

/**
 * Opaque token that pairs a request envelope with its response.
 * Absent on fire-and-forget messages; present on all request/response pairs (PIL-9).
 * UUID generation is provided here so callers never construct raw strings at call sites.
 */
public record CorrelationId(String value) {

    public CorrelationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Correlation id must not be blank.");
        }
        value = value.trim();
    }

    /** Generates a fresh, unpredictable correlation id. */
    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
