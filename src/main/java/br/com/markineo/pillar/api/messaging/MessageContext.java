package br.com.markineo.pillar.api.messaging;

public interface MessageContext {
    String senderId();
    void sync(Runnable task);
}
