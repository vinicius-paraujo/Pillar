package br.com.markineo.pillar.paper;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.api.Messaging;
import br.com.markineo.pillar.api.Pillar;
import br.com.markineo.pillar.api.Routing;

final class PillarFacade implements Pillar {
    
    PillarFacade() {
    }

    @Override
    public Messaging messaging() {
        throw new UnsupportedOperationException("Messaging is not yet implemented (PIL-82)");
    }

    @Override
    public Leases leases() {
        throw new UnsupportedOperationException("Leases is not yet implemented (PIL-83)");
    }

    @Override
    public Routing routing() {
        throw new UnsupportedOperationException("Routing is not yet implemented (PIL-84)");
    }
}
