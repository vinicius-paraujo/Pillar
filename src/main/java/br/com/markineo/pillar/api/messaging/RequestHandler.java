package br.com.markineo.pillar.api.messaging;

@FunctionalInterface
public interface RequestHandler<T extends Record, R extends Record> {
    R onRequest(T payload, MessageContext context);
}
