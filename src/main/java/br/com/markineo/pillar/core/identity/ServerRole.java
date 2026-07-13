package br.com.markineo.pillar.core.identity;

// Roles are operator-declared (skyblock, hub, minigame, etc.)
// and the fleet is heterogeneous by design; a fixed set would
// reject unknown roles.
public record ServerRole(String value) {

    public ServerRole {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Server role must not be blank.");
        }

        value = value.trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
