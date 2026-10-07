package com.geomusic.routing;

import java.util.Locale;
import java.util.Set;

/**
 * Kind of database access a unit of work needs.
 */
public enum AccessMode {
    /** Served by replicas of the user's region, falling back to the regional master. */
    READ,
    /** Served by the master of the region that owns the data. */
    WRITE;

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Default access mode derived from the HTTP method: safe methods read, everything else writes.
     */
    public static AccessMode forHttpMethod(String method) {
        return method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT)) ? READ : WRITE;
    }
}
