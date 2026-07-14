package br.com.markineo.pillar.paper;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.api.Messaging;
import br.com.markineo.pillar.api.Pillar;
import br.com.markineo.pillar.api.Routing;
import br.com.markineo.pillar.core.messaging.MessagingImpl;

final class PillarFacade implements Pillar {
    
    private final Messaging messaging;

    PillarFacade(MessagingImpl messaging) {
        this.messaging = messaging;
    }

    @Override
    public Messaging messaging() {
        return messaging;
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
