package com.markineo.pillar.core.task;

public final class PillarMessageTypes {

    public static final MessageType PING = new MessageType("pillar.ping");
    public static final MessageType PONG = new MessageType("pillar.pong");
    public static final MessageType ROUTE_PLAYER = new MessageType("pillar.route_player");

    private PillarMessageTypes() {
    }
}
