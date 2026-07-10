package com.markineo.pillar.redis;

import com.markineo.pillar.core.identity.ServerId;

public final class RedisKeys {

    private static final String PRESENCE_PREFIX = "pillar:presence:";
    private static final String INBOX_PREFIX = "pillar:inbox:";
    private static final String HEALTH_PREFIX = "pillar:health:";

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

    public static String inbox(ServerId id) {
        return INBOX_PREFIX + id.value();
    }

    public static String attempts(ServerId id) {
        return "pillar:attempts:" + id.value();
    }

    public static String health(ServerId id) {
        return HEALTH_PREFIX + id.value();
    }

    public static String dedup(ServerId id, String messageId) {
        return "pillar:dedup:" + id.value() + ":" + messageId;
    }
}
