package com.markineo.pillar.core.task;

/**
 * Single-point serialization contract for envelopes.
 *
 * <p>Lives in {@code core} so any package can depend on it; the concrete
 * implementation ({@code JsonEnvelopeCodec}) lives in {@code redis} where the shaded
 * Gson library is accessible. Swapping to a binary codec (MessagePack, Protobuf) later
 * only requires a new implementation and a wiring change — no envelope or transport code
 * changes.
 *
 * <p>Two-phase decode: {@code decode} reconstructs the envelope headers; {@code decodePayload}
 * further deserializes the opaque {@code payload} string into a concrete type. This
 * separation lets the transport layer inspect headers and dispatch to the right handler
 * before the handler decodes the payload.
 */
public interface EnvelopeCodec {

    /**
     * Serializes an envelope to a wire-ready string.
     *
     * @throws com.markineo.pillar.error.PillarException if serialization fails
     */
    String encode(Envelope envelope);

    /**
     * Deserializes a wire string back into an envelope.
     *
     * @throws com.markineo.pillar.error.PillarException if the string is malformed or
     *         the version is not recognized
     */
    Envelope decode(String wire);

    /**
     * Deserializes the opaque {@code payload} field of an already-decoded envelope into
     * a concrete type. The codec owns all JSON knowledge; handlers never import Gson.
     *
     * @throws com.markineo.pillar.error.PillarException if the payload does not match
     *         the expected shape
     */
    <T> T decodePayload(Envelope envelope, Class<T> type);
}
