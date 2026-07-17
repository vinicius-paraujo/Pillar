package br.com.markineo.pillar.core.identity;

/**
 * Identifies a single node in the fleet.
 *
 * @param value the node identifier, trimmed of surrounding whitespace
 */
public record ServerId(String value) {

    /**
     * @throws IllegalArgumentException if {@code value} is null or blank. A null lands
     *     here rather than in a {@link NullPointerException}.
     */
    public ServerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Server id must not be blank.");
        }
        value = value.trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
