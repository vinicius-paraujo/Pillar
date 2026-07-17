package br.com.markineo.pillar.redis.messaging;

import br.com.markineo.pillar.api.Messaging;
import br.com.markineo.pillar.api.PillarMessage;
import br.com.markineo.pillar.api.messaging.MessageContext;
import br.com.markineo.pillar.api.messaging.MessageHandler;
import br.com.markineo.pillar.api.messaging.RequestHandler;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.EnvelopeHandler;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.error.PillarException;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamPublisher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class MessagingImpl implements Messaging {

    private final StreamPublisher publisher;
    private final RequestSender requestSender;
    private final HandlerRegistry handlerRegistry;
    private final PlatformScheduler scheduler;
    private final EnvelopeCodec codec;
    private final ExecutorService workerPool;
    private final ServerId selfId;
    private final PresenceService presence;
    private final PillarLogger logger;

    public MessagingImpl(
            StreamPublisher publisher,
            RequestSender requestSender,
            HandlerRegistry handlerRegistry,
            PlatformScheduler scheduler,
            EnvelopeCodec codec,
            ExecutorService workerPool,
            ServerId selfId,
            PresenceService presence,
            PillarLogger logger
    ) {
        this.publisher = publisher;
        this.requestSender = requestSender;
        this.handlerRegistry = handlerRegistry;
        this.scheduler = scheduler;
        this.codec = codec;
        this.workerPool = workerPool;
        this.selfId = selfId;
        this.presence = presence;
        this.logger = logger;
    }

    @Override
    public <T extends Record> CompletableFuture<Void> send(PillarMessage<T> message, T payload, String targetServerId) {
        java.util.Objects.requireNonNull(message, "type");
        validateTarget(targetServerId);
        String json = codec.encodePayload(payload);
        return br.com.markineo.pillar.concurrent.PillarFutures.runGuarded(() -> {
            publisher.publish(new ServerId(targetServerId), Envelope.oneWay(new MessageType(message.id()), selfId, json));
        }, workerPool, scheduler);
    }

    @Override
    public <T extends Record> CompletableFuture<Void> broadcast(PillarMessage<T> message, T payload) {
        java.util.Objects.requireNonNull(message, "type");
        String json = codec.encodePayload(payload);
        return br.com.markineo.pillar.concurrent.PillarFutures.runGuarded(() -> {
            if (presence.isStale()) {
                throw new PillarException(
                        "Cannot broadcast: no fleet read has succeeded within the staleness window, "
                                + "so the target set is unknown. Nothing was published.");
            }
            for (ServerIdentity identity : presence.cachedFleet().members()) {
                if (!identity.id().equals(selfId)) {
                    publisher.publish(identity.id(), Envelope.oneWay(new MessageType(message.id()), selfId, json));
                }
            }
        }, workerPool, scheduler);
    }

    @Override
    public <T extends Record, R extends Record> CompletableFuture<R> request(PillarMessage<T> requestType, T payload, PillarMessage<R> responseType, String targetServerId) {
        java.util.Objects.requireNonNull(requestType, "requestType");
        java.util.Objects.requireNonNull(responseType, "responseType");
        validateTarget(targetServerId);
        String json = codec.encodePayload(payload);
        CompletableFuture<R> result = br.com.markineo.pillar.concurrent.PillarFutures.create(scheduler);
        CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<Envelope> future = requestSender.send(new ServerId(targetServerId), Envelope.request(new MessageType(requestType.id()), selfId, json));
                future.whenComplete((responseEnv, ex) -> {
                    if (ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        try {
                            R resp = codec.decodePayload(responseEnv, responseType.payloadClass());
                            result.complete(resp);
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    }
                });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }, workerPool);
        return result;
    }

    @Override
    public <T extends Record> void listen(PillarMessage<T> message, MessageHandler<T> handler) {
        handlerRegistry.register(new EnvelopeHandler() {
            @Override
            public MessageType type() {
                return new MessageType(message.id());
            }

            @Override
            public void handle(Envelope envelope, EnvelopeCodec c) {
                try {
                    T payload = c.decodePayload(envelope, message.payloadClass());
                    handler.onMessage(payload, new EnvelopeMessageContext(envelope.senderId(), scheduler));
                } catch (Exception e) {
                    logger.error("Error handling message type " + message.id(), e);
                }
            }
        });
    }

    @Override
    public <T extends Record, R extends Record> void handle(PillarMessage<T> requestType, PillarMessage<R> responseType, RequestHandler<T, R> handler) {
        handlerRegistry.register(new EnvelopeHandler() {
            @Override
            public MessageType type() {
                return new MessageType(requestType.id());
            }

            @Override
            public void handle(Envelope envelope, EnvelopeCodec c) {
                try {
                    T payload = c.decodePayload(envelope, requestType.payloadClass());
                    R responsePayload = handler.onRequest(payload, new EnvelopeMessageContext(envelope.senderId(), scheduler));
                    
                    envelope.correlationId().ifPresent(corrId -> {
                        String json = c.encodePayload(responsePayload);
                        Envelope responseEnv = Envelope.response(new MessageType(responseType.id()), corrId, selfId, json);
                        try {
                            publisher.publish(envelope.senderId(), responseEnv);
                        } catch (Exception e) {
                            logger.error("Failed to publish response for " + requestType.id() + " to " + envelope.senderId().value(), e);
                        }
                    });
                } catch (Exception e) {
                    logger.error("Uncaught exception in request handler for " + requestType.id(), e);
                }
            }
        });
    }

    private void validateTarget(String targetServerId) {
        if (targetServerId == null || targetServerId.isBlank()) {
            throw new IllegalArgumentException("Target server ID cannot be null or blank");
        }
        if (presence.isStale()) {
            return;
        }
        if (!presence.cachedFleet().contains(new ServerId(targetServerId))) {
            throw new IllegalArgumentException("Target server '" + targetServerId + "' is not present in the fleet");
        }
    }

    private static class EnvelopeMessageContext implements MessageContext {
        private final ServerId senderId;
        private final PlatformScheduler scheduler;

        public EnvelopeMessageContext(ServerId senderId, PlatformScheduler scheduler) {
            this.senderId = senderId;
            this.scheduler = scheduler;
        }

        @Override
        public String senderId() {
            return senderId.value();
        }

        @Override
        public void sync(Runnable task) {
            scheduler.runSync(task);
        }
    }
}
