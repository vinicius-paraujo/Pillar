package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.error.PillarException;

public class NoEligibleNodeException extends PillarException {
    
    private final ServerRole role;
    
    public NoEligibleNodeException(ServerRole role) {
        super("No eligible node found for role " + role.value());
        this.role = role;
    }
    
    public ServerRole role() {
        return role;
    }
}
