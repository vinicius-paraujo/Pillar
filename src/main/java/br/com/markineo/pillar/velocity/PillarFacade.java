package br.com.markineo.pillar.velocity;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.api.Messaging;
import br.com.markineo.pillar.api.Pillar;
import br.com.markineo.pillar.api.Routing;


final class PillarFacade implements Pillar {

    private final Messaging messaging;
    private final Leases leases;

    PillarFacade(Messaging messaging, Leases leases) {
        this.messaging = messaging;
        this.leases = leases;
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
        throw new UnsupportedOperationException("routing().move é uma operação de data-plane; chame de um servidor backend, ou use a API nativa do Velocity no proxy");
    }
}
