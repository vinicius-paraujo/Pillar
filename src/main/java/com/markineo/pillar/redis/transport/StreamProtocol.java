package com.markineo.pillar.redis.transport;

// Wire-protocol tokens shared by the stream publisher and consumer; a single source
// so a field-name or group change cannot desynchronize the two sides.
final class StreamProtocol {

    static final String FIELD_DATA = "data";
    static final String CONSUMER_GROUP = "pillar";

    private StreamProtocol() {
    }
}
