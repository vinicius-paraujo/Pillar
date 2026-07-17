package br.com.markineo.pillar.core.identity;

public record ServerIdentity(ServerId id, ServerRole role) {

    public ServerIdentity {
        if (id == null || role == null) {
            throw new IllegalArgumentException("Server identity requires both id and role.");
        }
    }
}
