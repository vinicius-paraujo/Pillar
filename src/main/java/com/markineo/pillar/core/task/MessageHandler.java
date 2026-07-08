package com.markineo.pillar.core.task;

public interface MessageHandler {

    MessageType type();

    // codec is passed so handlers can decode the payload without importing a JSON library.
    void handle(Envelope envelope, EnvelopeCodec codec);
}
