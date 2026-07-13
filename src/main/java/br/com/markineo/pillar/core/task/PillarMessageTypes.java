package br.com.markineo.pillar.core.task;

public final class PillarMessageTypes {

    public static final MessageType PING = new MessageType("pillar.ping");
    public static final MessageType PONG = new MessageType("pillar.pong");
    public static final MessageType ROUTE_PLAYER = new MessageType("pillar.route_player");
    public static final MessageType SEND_PLAYER_MESSAGE = new MessageType("pillar.send_player_message");
    public static final MessageType BROADCAST = new MessageType("pillar.broadcast");
    public static final MessageType RELOAD_CONFIG = new MessageType("pillar.reload_config");
    public static final MessageType RELOAD_CONFIG_ACK = new MessageType("pillar.reload_config_ack");

    private PillarMessageTypes() {
    }
}
