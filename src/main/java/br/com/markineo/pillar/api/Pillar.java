package br.com.markineo.pillar.api;

public interface Pillar {
    Messaging messaging();
    Leases leases();
    Routing routing();
}
