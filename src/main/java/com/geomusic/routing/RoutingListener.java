package com.geomusic.routing;

import java.sql.SQLException;

/**
 * Callback for routing decisions, used for metrics and tracing.
 */
public interface RoutingListener {

    RoutingListener NOOP = new RoutingListener() {
    };

    /**
     * @param failedAttempts number of candidate nodes that refused a connection before {@code node} succeeded
     */
    default void onConnectionRouted(RoutingContext.Route route, DbNode node, int failedAttempts) {
    }

    default void onNodeFailure(RoutingContext.Route route, DbNode node, SQLException error) {
    }
}
