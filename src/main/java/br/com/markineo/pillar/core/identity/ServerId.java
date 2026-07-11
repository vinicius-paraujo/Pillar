package br.com.markineo.pillar.core.identity;

public record ServerId(String value) {

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
