package br.com.markineo.pillar.api;

import br.com.markineo.pillar.api.messaging.MessageHandler;
import br.com.markineo.pillar.api.messaging.RequestHandler;
import java.util.concurrent.CompletableFuture;

public interface Messaging {

    <T extends Record> CompletableFuture<Void> send(PillarMessage<T> type, T payload, String targetServerId);

    <T extends Record> CompletableFuture<Void> broadcast(PillarMessage<T> type, T payload);

    <T extends Record, R extends Record> CompletableFuture<R> request(
            PillarMessage<T> requestType, 
            T requestPayload, 
            PillarMessage<R> responseType, 
            String targetServerId);

    <T extends Record> void listen(PillarMessage<T> type, MessageHandler<T> handler);

    <T extends Record, R extends Record> void handle(PillarMessage<T> requestType, PillarMessage<R> responseType, RequestHandler<T, R> handler);
}
