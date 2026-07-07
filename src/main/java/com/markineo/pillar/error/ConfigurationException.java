package com.markineo.pillar.error;

public class ConfigurationException extends PillarException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
