package com.geomusic.error;

/**
 * Requested entity does not exist in the shard/replica that served the request.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resource;
    private final Object id;

    public ResourceNotFoundException(String resource, Object id) {
        this(resource, id, "%s %s not found".formatted(resource, id));
    }

    public ResourceNotFoundException(String resource, Object id, String message) {
        super(message);
        this.resource = resource;
        this.id = id;
    }

    public String getResource() {
        return resource;
    }

    public Object getId() {
        return id;
    }
}
