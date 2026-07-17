package br.com.markineo.pillar.core.task;

import java.util.UUID;

// Open vocabulary parallel to ServerId/ServerRole: raw strings do not cross API boundaries.
public record CorrelationId(String value) {

    public CorrelationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Correlation id must not be blank.");
        }
        value = value.trim();
    }

    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
