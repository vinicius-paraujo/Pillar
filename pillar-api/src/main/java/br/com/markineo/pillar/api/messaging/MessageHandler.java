package br.com.markineo.pillar.api.messaging;

/**
 * Handles a one-way message registered through {@code Messaging#listen}. Delivery is
 * at-least-once, so the same {@code payload} can reach this handler more than once;
 * keep it idempotent.
 *
 * @param <T> the payload record type
 */
@FunctionalInterface
public interface MessageHandler<T extends Record> {

    /**
     * Called once per delivery of a message matching the registered type.
     *
     * @param payload the decoded message payload
     * @param context the sender and thread-hop utilities for this delivery
     */
    void onMessage(T payload, MessageContext context);
}
