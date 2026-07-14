package br.com.markineo.pillar.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a typed message definition for the Pillar network.
 * Binds a unique wire identifier to a specific record payload.
 *
 * @param <T> the payload record type
 */
public final class PillarMessage<T extends Record> {
    
    private static final ConcurrentHashMap<String, PillarMessage<?>> REGISTRY = new ConcurrentHashMap<>();

    private final String id;
    private final Class<T> payloadClass;

    private PillarMessage(String id, Class<T> payloadClass) {
        this.id = id;
        this.payloadClass = payloadClass;
    }

    /**
     * Creates and registers a new message definition.
     * 
     * @param id the namespaced identifier (e.g., "plugin:my_message")
     * @param payloadClass the Java record class representing the payload
     * @return the message definition
     * @throws IllegalArgumentException if the identifier is invalid, duplicated, or if the payload is not a valid serializable record.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Record> PillarMessage<T> of(String id, Class<T> payloadClass) {
        if (id == null || !id.contains(":")) {
            throw new IllegalArgumentException("Message identifier '" + id + "' is invalid. A namespace is mandatory (e.g., 'plugin:name').");
        }
        
        if (!payloadClass.isRecord()) {
            throw new IllegalArgumentException("Payload class " + payloadClass.getName() + " must be a Java Record.");
        }
        
        validateSerializable(payloadClass, payloadClass);

        PillarMessage<T> message = new PillarMessage<>(id, payloadClass);
        PillarMessage<?> existing = REGISTRY.putIfAbsent(id, message);
        
        if (existing != null) {
            // Allow idempotent registration of the exact same class for reload scenarios
            if (existing.payloadClass().equals(payloadClass)) {
                return (PillarMessage<T>) existing;
            }
            throw new IllegalArgumentException("Message identifier '" + id + "' is already registered with a different payload class: " + existing.payloadClass().getName());
        }
        
        return message;
    }

    /**
     * @return the wire identifier
     */
    public String id() {
        return id;
    }

    /**
     * @return the record payload class
     */
    public Class<T> payloadClass() {
        return payloadClass;
    }
    
    /**
     * Clears the static registry. Only meant for internal testing and lifecycle resets.
     */
    static void clearRegistry() {
        REGISTRY.clear();
    }

    private static void validateSerializable(Class<?> rootClass, Class<?> type) {
        if (type.isPrimitive() || 
            type == String.class || 
            type == UUID.class || 
            Number.class.isAssignableFrom(type) || 
            type == Boolean.class ||
            type == Character.class) {
            return;
        }
        
        if (List.class.isAssignableFrom(type)) {
            return;
        }
        
        if (type.isRecord()) {
            for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                Class<?> componentType = component.getType();
                if (componentType == type) {
                    throw new IllegalArgumentException("Recursive records are not supported for payloads (found in " + type.getName() + ")");
                }
                try {
                    validateSerializable(rootClass, componentType);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Payload " + rootClass.getName() + " has an unserializable component: '" + component.getName() + "' of type " + componentType.getSimpleName() + ". Allowed types are primitives, String, UUID, List, and other records.", e);
                }
            }
            return;
        }
        
        throw new IllegalArgumentException("Type " + type.getName() + " is not serializable.");
    }
}
