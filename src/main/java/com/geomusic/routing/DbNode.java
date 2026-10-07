package com.geomusic.routing;

import com.geomusic.domain.Region;

import java.util.Locale;
import java.util.Objects;

/**
 * Identity of a physical PostgreSQL node, e.g. {@code euro-master} or {@code asia-replica2}.
 *
 * @param region region the node is deployed in
 * @param type   master or replica
 * @param index  1-based replica number, {@code 0} for the master
 */
public record DbNode(Region region, NodeType type, int index) {

    public DbNode {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
    }

    public static DbNode master(Region region) {
        return new DbNode(region, NodeType.MASTER, 0);
    }

    public static DbNode replica(Region region, int index) {
        return new DbNode(region, NodeType.REPLICA, index);
    }

    /**
     * Stable node name used as routing lookup key, metric tag and span attribute.
     */
    public String id() {
        String prefix = region.name().toLowerCase(Locale.ROOT);
        return type == NodeType.MASTER ? prefix + "-master" : prefix + "-replica" + index;
    }

    @Override
    public String toString() {
        return id();
    }
}
