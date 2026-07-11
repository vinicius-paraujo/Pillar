package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.MessageHandler;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.error.PillarException;
import br.com.markineo.pillar.logger.PillarLogger;

// Lives in redis because it needs StreamPublisher, which touches Jedis.
public final class PingHandler implements MessageHandler {

    private final ServerId self;
    private final StreamPublisher publisher;
    private final PillarLogger logger;

    public PingHandler(ServerId self, StreamPublisher publisher, PillarLogger logger) {
        this.self = self;
        this.publisher = publisher;
        this.logger = logger;
    }

    @Override
    public MessageType type() {
        return PillarMessageTypes.PING;
    }

    @Override
    public void handle(Envelope ping, EnvelopeCodec codec) {
        // A ping without a correlationId has nobody to reply to.
        if (ping.correlationId().isEmpty()) {
            logger.warn("Received a ping with no correlation id from '" + ping.senderId() + "'; ignoring.");
            return;
        }

        try {
            // Pong carries no payload; "{}" satisfies Envelope's non-blank requirement.
            Envelope pong = Envelope.response(
                    PillarMessageTypes.PONG,
                    ping.correlationId().get(),
                    self,
                    "{}"
            );

            publisher.publish(ping.senderId(), pong);
        } catch (PillarException e) {
            logger.error("Failed to send pong to '" + ping.senderId() + "'.", e);
        }
    }
}
