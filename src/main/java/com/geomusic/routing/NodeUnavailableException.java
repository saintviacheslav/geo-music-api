package com.geomusic.routing;

import com.geomusic.domain.Region;

import java.sql.SQLTransientConnectionException;
import java.util.List;

/**
 * No node could serve the route: the regional master for a write, or every replica plus the master for a read.
 *
 * <p>Extends {@link java.sql.SQLException} because it is thrown from {@code DataSource.getConnection()};
 * Hibernate and Spring wrap it, the API layer unwraps it into HTTP 503.
 */
public class NodeUnavailableException extends SQLTransientConnectionException {

    private static final String SQL_STATE_CONNECTION_FAILURE = "08001";

    private final transient RoutingContext.Route route;
    private final List<String> attemptedNodes;

    public NodeUnavailableException(RoutingContext.Route route, List<String> attemptedNodes, Throwable cause) {
        super(message(route, attemptedNodes), SQL_STATE_CONNECTION_FAILURE, cause);
        this.route = route;
        this.attemptedNodes = List.copyOf(attemptedNodes);
    }

    public Region getRegion() {
        return route.region();
    }

    public AccessMode getMode() {
        return route.mode();
    }

    public List<String> getAttemptedNodes() {
        return attemptedNodes;
    }

    private static String message(RoutingContext.Route route, List<String> attempted) {
        return route.mode() == AccessMode.WRITE
                ? "Master node of region %s is unavailable".formatted(route.region())
                : "No database node of region %s is available for reads (tried %s)"
                        .formatted(route.region(), String.join(", ", attempted));
    }
}
