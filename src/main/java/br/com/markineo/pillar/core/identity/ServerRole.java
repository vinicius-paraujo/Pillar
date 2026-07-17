package br.com.markineo.pillar.core.identity;

// Roles are operator-declared (skyblock, hub, minigame, etc.)
// and the fleet is heterogeneous by design; a fixed set would
// reject unknown roles.
/**
 * The operator-declared role a server plays in the fleet (e.g. {@code "hub"},
 * {@code "skyblock"}). Placement and routing select among servers by role; there is
 * no fixed set of roles, so any value your network uses is valid.
 *
 * @param value the role name, trimmed of surrounding whitespace
 */
public record ServerRole(String value) {

    /**
     * @throws IllegalArgumentException if {@code value} is null or blank. A null lands
     *     here rather than in a {@link NullPointerException}.
     */
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
