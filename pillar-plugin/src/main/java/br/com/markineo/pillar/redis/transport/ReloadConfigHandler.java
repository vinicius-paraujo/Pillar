package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.config.Configurations;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.CorrelationId;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.EnvelopeHandler;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.logger.PillarLogger;

import java.util.Optional;

public final class ReloadConfigHandler implements EnvelopeHandler {

    private final Configurations configurations;
    private final ServerId self;
    private final StreamPublisher publisher;
    private final PillarLogger logger;

    public ReloadConfigHandler(Configurations configurations, ServerId self,
                               StreamPublisher publisher, PillarLogger logger) {
        this.configurations = configurations;
        this.self = self;
        this.publisher = publisher;
        this.logger = logger;
    }

    @Override
    public MessageType type() {
        return PillarMessageTypes.RELOAD_CONFIG;
    }

    @Override
    public void handle(Envelope envelope, EnvelopeCodec codec) {
        logger.info("Reload requested by " + envelope.senderId().value() + ".");
        String payload;
        try {
            configurations.reloadAll();
            payload = "{\"ok\":true}";
            logger.info("Reload completed successfully.");
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            payload = "{\"ok\":false,\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}";
            logger.warn("Reload failed: " + reason, ex);
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
