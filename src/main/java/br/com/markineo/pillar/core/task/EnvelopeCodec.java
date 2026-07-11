package br.com.markineo.pillar.core.task;

// Lives in core so any package can reference it; the concrete implementation
// (JsonEnvelopeCodec) lives in redis where the shaded Gson is accessible.
// Swapping to a binary codec only requires a new implementation and a wiring change.
public interface EnvelopeCodec {

    // @throws PillarException if serialization fails
    String encode(Envelope envelope);

    // @throws PillarException if the string is malformed or the version is unrecognized
    Envelope decode(String wire);

    // Two-phase decode: the transport dispatches on headers; the handler decodes the payload.
    // @throws PillarException if the payload does not match the expected shape
    <T> T decodePayload(Envelope envelope, Class<T> type);
}
