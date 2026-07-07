package com.markineo.pillar.redis;

import com.markineo.pillar.core.identity.ServerId;

public final class RedisKeys {

    private static final String PRESENCE_PREFIX = "pillar:presence:";

    private RedisKeys() {
    }

    public static String presence(ServerId id) {
        return PRESENCE_PREFIX + id.value();
    }

    public static String presencePattern() {
        return PRESENCE_PREFIX + "*";
    }

    public static ServerId presenceId(String key) {
        return new ServerId(key.substring(PRESENCE_PREFIX.length()));
    }
}
