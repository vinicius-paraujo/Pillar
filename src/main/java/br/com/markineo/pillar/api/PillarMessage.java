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
        
        validateSerializable(payloadClass, payloadClass, new java.util.HashSet<>());

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

    private static void validateSerializable(Class<?> rootClass, java.lang.reflect.Type type, java.util.Set<Class<?>> visited) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isPrimitive() || 
                clazz == String.class || 
                clazz == UUID.class || 
                Number.class.isAssignableFrom(clazz) || 
                clazz == Boolean.class ||
                clazz == Character.class) {
                return;
            }
            
            if (List.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("Raw List types are not supported. Use a generic List with a serializable type argument.");
            }
            
            if (clazz.isRecord()) {
                if (!visited.add(clazz)) {
                    throw new IllegalArgumentException("Recursive records are not supported for payloads (cycle detected involving " + clazz.getName() + ")");
                }
                
                for (java.lang.reflect.RecordComponent component : clazz.getRecordComponents()) {
                    try {
                        validateSerializable(rootClass, component.getGenericType(), visited);
                    } catch (IllegalArgumentException e) {
                        if (e.getMessage().startsWith("Payload ")) {
                            throw e;
                        }
                        throw new IllegalArgumentException("Payload " + rootClass.getName() + " has an unserializable component: '" + component.getName() + "' of type " + component.getGenericType().getTypeName() + ". " + e.getMessage(), e);
                    }
                }
                
                visited.remove(clazz);
                return;
            }
            
            throw new IllegalArgumentException("Type " + clazz.getName() + " is not serializable.");
        } else if (type instanceof java.lang.reflect.ParameterizedType pt) {
            if (pt.getRawType() instanceof Class<?> rawClass && List.class.isAssignableFrom(rawClass)) {
                java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                if (typeArgs.length == 1) {
                    validateSerializable(rootClass, typeArgs[0], visited);
                    return;
                }
            }
            throw new IllegalArgumentException("Parameterized type " + type.getTypeName() + " is not supported.");
        } else {
            throw new IllegalArgumentException("Type " + type.getTypeName() + " is not supported.");
        }
    }
}
