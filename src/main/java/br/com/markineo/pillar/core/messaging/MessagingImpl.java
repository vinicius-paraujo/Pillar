package br.com.markineo.pillar.core.messaging;

import br.com.markineo.pillar.api.Messaging;
import br.com.markineo.pillar.api.PillarMessage;
import br.com.markineo.pillar.api.messaging.MessageContext;
import br.com.markineo.pillar.api.messaging.MessageHandler;
import br.com.markineo.pillar.api.messaging.RequestHandler;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import com.google.gson.Gson;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class MessagingImpl implements Messaging {

    private final StreamPublisher publisher;
    private final RequestSender requestSender;
    private final HandlerRegistry handlerRegistry;
    private final PlatformScheduler scheduler;
    private final Gson gson;
    private final ExecutorService workerPool;
    private final ServerId selfId;
    private final FleetView fleetView;
    private final PillarLogger logger;

    public MessagingImpl(
            StreamPublisher publisher,
            RequestSender requestSender,
            HandlerRegistry handlerRegistry,
            PlatformScheduler scheduler,
            Gson gson,
            ExecutorService workerPool,
            ServerId selfId,
            FleetView fleetView,
            PillarLogger logger
    ) {
        this.publisher = publisher;
        this.requestSender = requestSender;
        this.handlerRegistry = handlerRegistry;
        this.scheduler = scheduler;
        this.gson = gson;
        this.workerPool = workerPool;
        this.selfId = selfId;
        this.fleetView = fleetView;
        this.logger = logger;
    }

    @Override
    public <T extends Record> CompletableFuture<Void> send(PillarMessage<T> message, T payload, String targetServerId) {
        String json = gson.toJson(payload);
        return CompletableFuture.runAsync(() -> {
            publisher.publish(new ServerId(targetServerId), Envelope.oneWay(new MessageType(message.id()), selfId, json));
        }, workerPool);
    }

    @Override
    public <T extends Record> CompletableFuture<Void> broadcast(PillarMessage<T> message, T payload) {
        String json = gson.toJson(payload);
        return CompletableFuture.runAsync(() -> {
            fleetView.snapshot().ifPresent(snapshot -> {
                for (ServerIdentity identity : snapshot.members()) {
                    publisher.publish(identity.id(), Envelope.oneWay(new MessageType(message.id()), selfId, json));
                }
            });
        }, workerPool);
    }

    @Override
    public <T extends Record, R extends Record> CompletableFuture<R> request(PillarMessage<T> requestType, T payload, PillarMessage<R> responseType, String targetServerId) {
        String json = gson.toJson(payload);
        CompletableFuture<R> result = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<Envelope> future = requestSender.send(new ServerId(targetServerId), Envelope.request(new MessageType(requestType.id()), selfId, json));
                future.whenComplete((responseEnv, ex) -> {
                    if (ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        try {
                            R resp = gson.fromJson(responseEnv.payload(), responseType.payloadClass());
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
        handlerRegistry.register(new br.com.markineo.pillar.core.task.MessageHandler() {
            @Override
            public MessageType type() {
                return new MessageType(message.id());
            }

            @Override
            public void handle(Envelope envelope, EnvelopeCodec codec) {
                T payload = gson.fromJson(envelope.payload(), message.payloadClass());
                MessageContext ctx = new MessageContext() {
                    @Override
                    public String senderId() {
                        return envelope.senderId().value();
                    }
                    @Override
                    public void sync(Runnable task) {
                        scheduler.runSync(task);
                    }
                };
                handler.onMessage(payload, ctx);
            }
        });
    }

    @Override
    public <T extends Record, R extends Record> void handle(PillarMessage<T> requestType, PillarMessage<R> responseType, RequestHandler<T, R> handler) {
        handlerRegistry.register(new br.com.markineo.pillar.core.task.MessageHandler() {
            @Override
            public MessageType type() {
                return new MessageType(requestType.id());
            }

            @Override
            public void handle(Envelope envelope, EnvelopeCodec codec) {
                T payload = gson.fromJson(envelope.payload(), requestType.payloadClass());
                MessageContext ctx = new MessageContext() {
                    @Override
                    public String senderId() {
                        return envelope.senderId().value();
                    }
                    @Override
                    public void sync(Runnable task) {
                        scheduler.runSync(task);
                    }
                };
                
                R responsePayload = handler.onRequest(payload, ctx);
                
                envelope.correlationId().ifPresent(corrId -> {
                    String json = gson.toJson(responsePayload);
                    Envelope responseEnv = Envelope.response(new MessageType(responseType.id()), corrId, selfId, json);
                    try {
                        publisher.publish(envelope.senderId(), responseEnv);
                    } catch (Exception e) {
                        logger.error("Failed to publish response for " + requestType.id() + " to " + envelope.senderId().value(), e);
                    }
                });
            }
        });
    }
}
