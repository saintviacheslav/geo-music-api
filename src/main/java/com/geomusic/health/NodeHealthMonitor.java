package com.geomusic.health;

import com.geomusic.config.GeoProperties;
import com.geomusic.routing.DbNode;
import com.geomusic.routing.GeoNodeRegistry;
import com.geomusic.routing.NodeHealthRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically checks out a connection from every node pool (in parallel) and records the result in
 * {@link NodeHealthRegistry}. This is what brings a node back into rotation after the router marked it DOWN.
 *
 * <p>Also exports {@code geo.db.node.up} (1/0) per node.
 */
@Component
public class NodeHealthMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthMonitor.class);

    private final GeoNodeRegistry nodes;
    private final NodeHealthRegistry registry;
    private final Duration interval;
    private final int validationTimeoutSeconds;
    private volatile ScheduledExecutorService scheduler;

    public NodeHealthMonitor(GeoNodeRegistry nodes, NodeHealthRegistry registry,
                             GeoProperties properties, MeterRegistry meterRegistry) {
        this.nodes = nodes;
        this.registry = registry;
        this.interval = properties.health().probeInterval();
        this.validationTimeoutSeconds = (int) Math.max(1, properties.health().probeTimeout().toSeconds());

        for (DbNode node : nodes.all()) {
            Gauge.builder("geo.db.node.up", registry,
                            r -> r.status(node).state() == NodeHealthRegistry.State.UP ? 1 : 0)
                    .description("1 if the database node answered the last probe")
                    .tag("node", node.id())
                    .tag("region", node.region().name())
                    .tag("node.type", node.type().name())
                    .register(meterRegistry);
        }
    }

    /**
     * Probes all nodes concurrently and waits for the slowest one.
     */
    public void probeAll() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            nodes.all().forEach(node -> executor.submit(() -> probe(node)));
        }
    }

    private void probe(DbNode node) {
        long started = System.nanoTime();
        try (Connection connection = nodes.dataSource(node).getConnection()) {
            if (!connection.isValid(validationTimeoutSeconds)) {
                throw new SQLException("Connection validation failed");
            }
            registry.recordProbeSuccess(node, Duration.ofNanos(System.nanoTime() - started));
        } catch (SQLException | RuntimeException ex) {
            registry.recordProbeFailure(node, ex);
        }
    }

    private void probeAllSafely() {
        try {
            probeAll();
        } catch (RuntimeException ex) {
            log.error("Database health probe failed", ex);
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("geo-health-probe").daemon().factory());
        executor.scheduleWithFixedDelay(this::probeAllSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = executor;
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = scheduler;
        if (executor != null) {
            executor.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
