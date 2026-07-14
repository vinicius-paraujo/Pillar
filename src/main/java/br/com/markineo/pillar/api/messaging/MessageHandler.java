package br.com.markineo.pillar.api.messaging;

@FunctionalInterface
public interface MessageHandler<T extends Record> {
    void onMessage(T payload, MessageContext context);
}
