package br.com.markineo.pillar.core.task;

// Open vocabulary: message types are handler-declared, not a fixed enum.
// Consumers register their own strings (e.g. "pillar.ping", "skyblock.task.assign").
/**
 * The wire-level identifier a message envelope carries. {@link
 * br.com.markineo.pillar.api.PillarMessage} wraps one of these alongside its payload
 * type; consumers declare their own identifiers, there is no fixed set.
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
