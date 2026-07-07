package com.markineo.pillar.redis;

public enum ConnectionState {

    // Pool created, first reachability check not yet passed.
    STARTING,

    // Redis reachable; work that needs Redis may proceed.
    READY,

    // Redis unreachable; a reconnect loop is trying to restore it.
    DEGRADED,

    // Connector closed; terminal, never leaves this state.
    SHUT_DOWN
}
