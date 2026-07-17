package br.com.markineo.pillar.api.messaging;

/**
 * Handles a request registered through {@code Messaging#handle} and returns the
 * response payload. If this handler throws, Pillar logs the exception on the
 * responder side; the requester does not learn the cause and simply times out.
 *
 * @param <T> the request payload record type
 * @param <R> the response payload record type
 */
@FunctionalInterface
public interface RequestHandler<T extends Record, R extends Record> {

    /**
     * Called once per delivery of a request matching the registered type.
     *
     * @param payload the decoded request payload
     * @param context the sender and thread-hop utilities for this delivery
     * @return the response payload to send back
     */
    R onRequest(T payload, MessageContext context);
}
