package br.com.markineo.pillar.error;

/**
 * Thrown when a config or lang file is missing, is malformed, or is missing a
 * required key. Raised at load time; Pillar does not fall back to a default value.
 */
public class ConfigurationException extends PillarException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
