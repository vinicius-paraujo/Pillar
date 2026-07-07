package com.markineo.pillar.core.task;

// Open vocabulary: message types are handler-declared, not a fixed enum.
// Consumers register their own strings (e.g. "pillar.ping", "skyblock.task.assign").
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
