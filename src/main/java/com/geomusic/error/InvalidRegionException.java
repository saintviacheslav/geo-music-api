package com.geomusic.error;

/**
 * The {@code X-User-Region} header holds an unknown region.
 */
public class InvalidRegionException extends RuntimeException {

    private final String value;

    public InvalidRegionException(String value) {
        super("Unknown region '%s' in header X-User-Region".formatted(value));
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
