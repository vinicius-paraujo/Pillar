package br.com.markineo.pillar.velocity.handler;

import com.google.gson.Gson;
import br.com.markineo.pillar.core.messaging.BroadcastRequest;
import br.com.markineo.pillar.core.task.CorrelationId;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.EnvelopeHandler;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.logger.PillarLogger;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class BroadcastHandler implements EnvelopeHandler {

    private final ProxyServer server;
    private final Gson gson;
    private final PillarLogger logger;

    public BroadcastHandler(ProxyServer server, Gson gson, PillarLogger logger) {
        this.server = server;
        this.gson = gson;
        this.logger = logger;
    }

    @Override
    public MessageType type() {
        return PillarMessageTypes.BROADCAST;
    }

    @Override
    public void handle(Envelope envelope, EnvelopeCodec codec) {
        BroadcastRequest request;
        try {
            request = gson.fromJson(envelope.payload(), BroadcastRequest.class);
        } catch (Exception e) {
            logger.error("Failed to parse BroadcastRequest from envelope " + envelope.correlationId().map(CorrelationId::value).orElse("none"), e);
            return;
        }

        try {
            Component component = MiniMessage.miniMessage().deserialize(request.miniMessageContent());
            server.sendMessage(component);
            logger.debug("Broadcasted message to network.");
        } catch (Exception e) {
            logger.error("Failed to deserialize or send broadcast MiniMessage", e);
        }
    }
}
