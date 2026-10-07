package com.geomusic.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared view of node availability.
 *
 * <p>Updated from two sources: real traffic ({@link GeoRoutingDataSource} marks a node DOWN as soon as it
 * refuses a connection) and the periodic probe ({@link com.geomusic.health.NodeHealthMonitor}) which brings
 * nodes back UP. The router skips DOWN replicas so that a dead node costs at most one connection timeout per
 * probe interval instead of one per request.
 */
public class NodeHealthRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthRegistry.class);

    public enum State { UNKNOWN, UP, DOWN }

    /**
     * @param state     current state
     * @param changedAt when the state last changed
     * @param checkedAt time of the last health probe, {@code null} if never probed
     * @param latency   duration of the last successful probe
     * @param error     last error message while DOWN
     */
    public record NodeStatus(State state, Instant changedAt, Instant checkedAt, Duration latency, String error) {
    }

    private final Map<DbNode, NodeStatus> statuses = new ConcurrentHashMap<>();

    public NodeHealthRegistry(Collection<DbNode> nodes) {
        Instant now = Instant.now();
        nodes.forEach(node -> statuses.put(node, new NodeStatus(State.UNKNOWN, now, null, null, null)));
    }

    public NodeStatus status(DbNode node) {
        NodeStatus status = statuses.get(node);
        return status != null ? status : new NodeStatus(State.UNKNOWN, Instant.now(), null, null, null);
    }

    /**
     * UNKNOWN counts as available: right after startup nodes have not been probed yet.
     */
    public boolean isAvailable(DbNode node) {
        return status(node).state() != State.DOWN;
    }

    /**
     * Called on every successful connection checkout, so it avoids writes when nothing changes.
     */
    public void markUp(DbNode node) {
        NodeStatus current = statuses.get(node);
        if (current != null && current.state() == State.UP) {
            return;
        }
        statuses.compute(node, (n, cur) -> transition(n, cur, State.UP, cur == null ? null : cur.checkedAt(),
                cur == null ? null : cur.latency(), null));
    }

    public void markDown(DbNode node, Throwable error) {
        statuses.compute(node, (n, cur) -> transition(n, cur, State.DOWN, cur == null ? null : cur.checkedAt(),
                null, describe(error)));
    }

    public void recordProbeSuccess(DbNode node, Duration latency) {
        statuses.compute(node, (n, cur) -> transition(n, cur, State.UP, Instant.now(), latency, null));
    }

    public void recordProbeFailure(DbNode node, Throwable error) {
        statuses.compute(node, (n, cur) -> transition(n, cur, State.DOWN, Instant.now(), null, describe(error)));
    }

    public Map<DbNode, NodeStatus> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
    }

    private static NodeStatus transition(DbNode node, NodeStatus current, State newState,
                                         Instant checkedAt, Duration latency, String error) {
        boolean changed = current == null || current.state() != newState;
        if (changed && newState == State.DOWN) {
            log.warn("Database node {} is DOWN: {}", node.id(), error);
        } else if (changed && newState == State.UP && current != null && current.state() == State.DOWN) {
            log.info("Database node {} is back UP", node.id());
        }
        Instant changedAt = changed ? Instant.now() : current.changedAt();
        return new NodeStatus(newState, changedAt, checkedAt, latency, error);
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
}
