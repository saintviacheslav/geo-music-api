package com.geomusic.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Geographic region. Every region owns one shard: a master node for writes and replica nodes for reads.
 */
public enum Region {
    AMER,
    EURO,
    ASIA;

    /**
     * Case-insensitive parsing that never throws.
     *
     * @param value raw value, e.g. from the {@code X-User-Region} header
     * @return the region or empty if the value is blank or unknown
     */
    public static Optional<Region> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
