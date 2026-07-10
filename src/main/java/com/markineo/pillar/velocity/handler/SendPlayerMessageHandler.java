package com.markineo.pillar.velocity.handler;

import com.google.gson.Gson;
import com.markineo.pillar.core.messaging.SendPlayerMessageRequest;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.EnvelopeCodec;
import com.markineo.pillar.core.task.MessageHandler;
import com.markineo.pillar.core.task.MessageType;
import com.markineo.pillar.core.task.PillarMessageTypes;
import com.markineo.pillar.logger.PillarLogger;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Optional;

public final class SendPlayerMessageHandler implements MessageHandler {

    private final ProxyServer server;
    private final Gson gson;
    private final PillarLogger logger;

    public SendPlayerMessageHandler(ProxyServer server, Gson gson, PillarLogger logger) {
        this.server = server;
        this.gson = gson;
        this.logger = logger;
    }

    @Override
    public MessageType type() {
        return PillarMessageTypes.SEND_PLAYER_MESSAGE;
    }

    @Override
    public void handle(Envelope envelope, EnvelopeCodec codec) {
        SendPlayerMessageRequest request;
        try {
            request = gson.fromJson(envelope.payload(), SendPlayerMessageRequest.class);
        } catch (Exception e) {
            logger.error("Failed to parse SendPlayerMessageRequest from envelope " + envelope.correlationId().map(com.markineo.pillar.core.task.CorrelationId::value).orElse("none"), e);
            return;
        }

        Optional<Player> playerOpt = server.getPlayer(request.playerId());
        if (playerOpt.isEmpty()) {
            logger.debug("Failed to deliver message to " + request.playerId() + ": player is offline.");
            return;
        }
        Player player = playerOpt.get();

        try {
            Component component = MiniMessage.miniMessage().deserialize(request.miniMessageContent());
            player.sendMessage(component);
            logger.debug("Delivered message to player " + player.getUsername() + ".");
        } catch (Exception e) {
            logger.error("Failed to deserialize or send MiniMessage to player " + player.getUsername(), e);
        }
    }
}
