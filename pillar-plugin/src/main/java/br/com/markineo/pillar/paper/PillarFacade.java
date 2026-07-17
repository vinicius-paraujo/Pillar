package br.com.markineo.pillar.paper;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.api.Messaging;
import br.com.markineo.pillar.api.Pillar;
import br.com.markineo.pillar.api.Routing;


final class PillarFacade implements Pillar {
    
    private final Messaging messaging;
    private final Leases leases;
    private final Routing routing;

    PillarFacade(Messaging messaging, Leases leases, Routing routing) {
        this.messaging = messaging;
        this.leases = leases;
        this.routing = routing;
    }

    @Override
    public Messaging messaging() {
        return messaging;
    }

    @Override
    public Leases leases() {
        return leases;
    }

    @Override
    public Routing routing() {
        return routing;
    }
}
