package com.markineo.pillar.core.task;

public final class PillarMessageTypes {

    public static final MessageType PING = new MessageType("pillar.ping");
    public static final MessageType PONG = new MessageType("pillar.pong");

    private PillarMessageTypes() {
    }
}
