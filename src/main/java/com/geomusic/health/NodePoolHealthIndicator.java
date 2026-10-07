package com.geomusic.health;

import com.geomusic.routing.DbNode;
import com.geomusic.routing.GeoNodeRegistry;
import com.geomusic.routing.NodeHealthRegistry;
import com.geomusic.routing.NodeType;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health of a single node's connection pool.
 *
 * <p>Reports the cached result of {@link NodeHealthMonitor} plus live HikariCP statistics, so calling
 * {@code /actuator/health} never blocks on a dead node. A dead replica is {@code DEGRADED} (reads fail over),
 * a dead master is {@code DOWN} (writes of that shard fail).
 */
public class NodePoolHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED");

    private final DbNode node;
    private final GeoNodeRegistry nodes;
    private final NodeHealthRegistry registry;

    public NodePoolHealthIndicator(DbNode node, GeoNodeRegistry nodes, NodeHealthRegistry registry) {
        this.node = node;
        this.nodes = nodes;
        this.registry = registry;
    }

    @Override
    public Health health() {
        NodeHealthRegistry.NodeStatus status = registry.status(node);
        Health.Builder builder = switch (status.state()) {
            case UP -> Health.up();
            case DOWN -> node.type() == NodeType.MASTER ? Health.down() : Health.status(DEGRADED);
            case UNKNOWN -> Health.unknown();
        };

        builder.withDetail("region", node.region())
                .withDetail("role", node.type())
                .withDetail("url", nodes.url(node))
                .withDetail("since", status.changedAt());
        if (status.checkedAt() != null) {
            builder.withDetail("lastCheck", status.checkedAt());
        }
        if (status.latency() != null) {
            builder.withDetail("latencyMs", status.latency().toMillis());
        }
        if (status.error() != null) {
            builder.withDetail("error", status.error());
        }
        nodes.pool(node).ifPresent(pool -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("active", pool.getActiveConnections());
            stats.put("idle", pool.getIdleConnections());
            stats.put("total", pool.getTotalConnections());
            stats.put("awaiting", pool.getThreadsAwaitingConnection());
            builder.withDetail("pool", stats);
        });
        return builder.build();
    }
}
