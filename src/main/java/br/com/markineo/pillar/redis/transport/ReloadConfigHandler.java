package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.config.Configurations;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.CorrelationId;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.MessageHandler;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;

import java.util.Optional;

public final class ReloadConfigHandler implements MessageHandler {

    private final Configurations configurations;
    private final ServerId self;
    private final StreamPublisher publisher;

    public ReloadConfigHandler(Configurations configurations, ServerId self, StreamPublisher publisher) {
        this.configurations = configurations;
        this.self = self;
        this.publisher = publisher;
    }

    @Override
    public MessageType type() {
        return PillarMessageTypes.RELOAD_CONFIG;
    }

    @Override
    public void handle(Envelope envelope, EnvelopeCodec codec) {
        String payload;
        try {
            configurations.reloadAll();
            payload = "{\"ok\":true}";
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            payload = "{\"ok\":false,\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}";
        }

        Optional<CorrelationId> correlation = envelope.correlationId();
        if (correlation.isPresent()) {
            Envelope response = Envelope.response(
                    PillarMessageTypes.RELOAD_CONFIG_ACK,
                    correlation.get(),
                    self,
                    payload
            );
            publisher.publish(envelope.senderId(), response);
        }
    }
}
