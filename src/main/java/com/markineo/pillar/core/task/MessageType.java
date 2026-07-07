package com.markineo.pillar.core.task;

/**
 * Open-vocabulary discriminator that identifies which handler processes an envelope.
 * A record rather than an enum so new types can be introduced without touching Pillar
 * core — each consumer module registers its own strings (e.g. "pillar.ping",
 * "skyblock.task.assign").
 */
public record MessageType(String value) {

    public MessageType {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Message type must not be blank.");
        }
        value = value.trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
