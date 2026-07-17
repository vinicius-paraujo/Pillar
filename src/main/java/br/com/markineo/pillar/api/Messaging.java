package br.com.markineo.pillar.api;

import br.com.markineo.pillar.api.messaging.MessageHandler;
import br.com.markineo.pillar.api.messaging.RequestHandler;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-server messaging: one-way sends, request/response, and fleet-wide broadcast.
 * Every send is at-least-once. Under connection instability a node can process a
 * message and die before acknowledging it, so the same message may arrive twice.
 * Write handlers so processing the same payload twice produces the same result as
 * processing it once.
 *
 * <p>Handler execution is async by default; nothing here runs on the game thread
 * unless the handler hops there itself through {@link
 * br.com.markineo.pillar.api.messaging.MessageContext#sync}.
 *
 * <p>The returned futures carry delivery failures. A transport failure completes the
 * future exceptionally with {@link br.com.markineo.pillar.error.PublishFailedException};
 * nothing is thrown to the caller and nothing is logged at this layer. Discarding the
 * future from {@link #send} or {@link #broadcast} discards that signal, so a message
 * lost to a Redis outage leaves no trace anywhere. Attach {@code exceptionally} or
 * {@code whenComplete} if losing the message matters.
 *
 * <p>Every {@link java.util.concurrent.CompletableFuture} returned by this interface
 * completes on an internal Pillar thread, never the platform's main thread. Which
 * thread varies by call and is not part of the contract; a callback chained with
 * {@code thenAccept}/{@code thenApply} runs on whatever thread that was, so touching
 * Bukkit or Velocity API from it is unsafe. Hop to the main thread first, the same way
 * handlers do through {@link br.com.markineo.pillar.api.messaging.MessageContext#sync}.
 * Calling {@code join()} or {@code get()} on the returned future from the main thread
 * throws {@link IllegalStateException} instead of blocking; this applies anywhere on
 * the main thread, including plugin startup, not only mid-tick.
 */
public interface Messaging {

    /**
     * Sends {@code payload} to a single server. Fire-and-forget: the returned future
     * completes once the message is handed off, not once it is processed.
     *
     * <p>Pillar checks {@code targetServerId} against this node's cached fleet before
     * starting any asynchronous work, so an unknown target fails synchronously rather
     * than through the returned future. The check is skipped whenever no fleet read
     * has succeeded within the staleness window: at startup, and for as long as a
     * Redis outage runs past it. Skipped means the send proceeds, because an unknown
     * target and one this node has not read yet are the same thing from here; the
     * envelope waits in the target's inbox stream for a node that may never read it.
     *
     * <p>A null {@code payload} is not rejected: it is serialized as JSON {@code null}
     * and delivered as-is, and the receiving handler decodes it as a null payload
     * object. A null {@code type} is rejected synchronously, the same as an unknown
     * target.
     *
     * @param type the message definition
     * @param payload the message payload
     * @param targetServerId the destination server ID
     * @param <T> the payload record type
     * @return a future completed once the message is handed off for delivery
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code targetServerId} is blank, or absent
     *     from a cached fleet that has a read inside the staleness window
     */
    <T extends Record> CompletableFuture<Void> send(PillarMessage<T> type, T payload, String targetServerId);

    /**
     * Sends {@code payload} to every other active node in the fleet, including
     * proxies.
     *
     * <p>The sending node is excluded. A handler this node registered through {@link
     * #listen} does not see this node's own broadcast, so anything the local node
     * must also act on (dropping a cache entry, for example) has to be done directly
     * alongside the call.
     *
     * <p>A broadcast needs a known fleet. If no presence read has succeeded within the
     * staleness window, the returned future completes exceptionally with {@link
     * br.com.markineo.pillar.error.PillarException} instead of reporting success for a
     * message that reached no one. A fleet that is known and genuinely empty is a
     * different case: the future completes normally, having published nothing.
     *
     * <p>A null {@code payload} is not rejected: it is serialized as JSON {@code null}
     * and delivered as-is. A null {@code type} is rejected synchronously.
     *
     * @param type the message definition
     * @param payload the message payload
     * @param <T> the payload record type
     * @return a future completed once the message is handed off for delivery
     * @throws NullPointerException if {@code type} is null
     */
    <T extends Record> CompletableFuture<Void> broadcast(PillarMessage<T> type, T payload);

    /**
     * Sends {@code requestPayload} to {@code targetServerId} and waits for its
     * response.
     *
     * <p>Pillar checks {@code targetServerId} against this node's cached fleet before
     * starting any asynchronous work, so an unknown target fails synchronously rather
     * than through the returned future. The check is skipped whenever no fleet read
     * has succeeded within the staleness window: at startup, and for as long as a
     * Redis outage runs past it. In that state the request proceeds and resolves
     * through the timeout below. Once the request is sent, the returned future
     * completes exceptionally with {@link br.com.markineo.pillar.error.TimeoutPillarException}
     * if no response arrives before the default timeout. If the target's {@link
     * br.com.markineo.pillar.api.messaging.RequestHandler} throws instead of
     * responding, that exception is not sent back over the wire: the caller still
     * sees a plain {@link br.com.markineo.pillar.error.TimeoutPillarException}, and
     * the actual cause is only visible in the target node's logs.
     *
     * <p>Never point {@code targetServerId} at the calling node. Pillar does not reject
     * it: the request comes back through this node's own dispatch, matches the
     * correlation this call just registered, and completes the future with the request
     * envelope. The {@link br.com.markineo.pillar.api.messaging.RequestHandler} never
     * runs, and the caller decodes its own request payload as the response. Branch on
     * the target before calling if it can be this node.
     *
     * <p>A null {@code requestPayload} is not rejected: it is serialized as JSON
     * {@code null} and delivered as-is. A null {@code requestType} or {@code
     * responseType} is rejected synchronously, the same as an unknown target.
     *
     * @param requestType the request message definition
     * @param requestPayload the request payload
     * @param responseType the expected response message definition
     * @param targetServerId the destination server ID
     * @param <T> the request payload record type
     * @param <R> the response payload record type
     * @return a future completed with the decoded response payload
     * @throws NullPointerException if {@code requestType} or {@code responseType} is
     *     null
     * @throws IllegalArgumentException if {@code targetServerId} is blank, or absent
     *     from a cached fleet that has a read inside the staleness window
     */
    <T extends Record, R extends Record> CompletableFuture<R> request(
            PillarMessage<T> requestType,
            T requestPayload,
            PillarMessage<R> responseType,
            String targetServerId);

    /**
     * Registers a handler for one-way messages of {@code type}. Replaces any handler
     * previously registered for the same type.
     *
     * @param type the message definition to handle
     * @param handler the handler to invoke on each delivery
     * @param <T> the payload record type
     */
    <T extends Record> void listen(PillarMessage<T> type, MessageHandler<T> handler);

    /**
     * Registers a handler that answers {@code requestType} with {@code responseType}.
     * A handler that throws is logged; the requester's future is not failed early and
     * still runs to the default timeout.
     *
     * @param requestType the request message definition to handle
     * @param responseType the response message definition to answer with
     * @param handler the handler to invoke on each delivery
     * @param <T> the request payload record type
     * @param <R> the response payload record type
     */
    <T extends Record, R extends Record> void handle(PillarMessage<T> requestType, PillarMessage<R> responseType, RequestHandler<T, R> handler);
}
