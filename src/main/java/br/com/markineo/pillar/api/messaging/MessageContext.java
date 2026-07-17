package br.com.markineo.pillar.api.messaging;

/**
 * Passed to every {@link MessageHandler} and {@link RequestHandler} invocation.
 * Handlers run off the game thread by default; use {@link #sync} before touching
 * anything that requires main-thread access.
 */
public interface MessageContext {

    /**
     * The server ID that sent this message.
     *
     * @return the sender's server ID
     */
    String senderId();

    /**
     * Runs {@code task} where the platform requires main-thread access. Whether it
     * runs inline or later depends on the platform, and this method does not wait for
     * it either way.
     *
     * <p>On Paper, a caller already on the main thread runs {@code task} inline before
     * this returns; a caller off it gets {@code task} queued for a later tick, and
     * this returns before {@code task} has run. On Velocity there is no main thread:
     * {@code task} always runs inline on the calling thread, which inside a handler is
     * Pillar's bounded dispatch pool, so long work there occupies a dispatch worker
     * for its full duration.
     *
     * <p>Do not read state that {@code task} writes on the line after this call. On
     * Paper off the main thread that read lands before the write; on Velocity it lands
     * after. Continue the work inside {@code task} instead.
     *
     * @param task the work to run
     */
    void sync(Runnable task);
}
