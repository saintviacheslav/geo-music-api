package com.geomusic.routing;

/**
 * Role of a PostgreSQL node inside its region.
 */
public enum NodeType {
    /** Accepts writes for the region's shard. */
    MASTER,
    /** Hot-standby streaming replica of the regional master, read-only. */
    REPLICA
}
