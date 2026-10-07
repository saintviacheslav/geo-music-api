package com.geomusic.routing;

import com.geomusic.domain.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routing {@link DataSource} that maps every connection request to a concrete PostgreSQL node.
 *
 * <ul>
 *   <li><b>WRITE</b> — the master of the route's region (the shard owning the object or user).</li>
 *   <li><b>READ</b> — replicas of the route's region (the user's region) in round-robin order; replicas
 *       currently marked DOWN are skipped; the regional master is the last resort.</li>
 * </ul>
 *
 * <p>Fail-over happens at connection checkout: when a node refuses a connection the next candidate is tried
 * and the node is marked DOWN in {@link NodeHealthRegistry}. When no candidate is left a
 * {@link NodeUnavailableException} is thrown, which the API layer turns into HTTP 503.
 *
 * <p>The route is read from {@link RoutingContext} when the transaction opens its connection, so it must be
 * set before the transaction starts — use {@link GeoTransactionExecutor}.
 */
public class GeoRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(GeoRoutingDataSource.class);

    private final GeoNodeRegistry nodes;
    private final NodeHealthRegistry health;
    private final RoutingListener listener;
    private final Region defaultRegion;
    private final Map<Region, AtomicInteger> roundRobin = new EnumMap<>(Region.class);

    public GeoRoutingDataSource(GeoNodeRegistry nodes, NodeHealthRegistry health,
                                RoutingListener listener, Region defaultRegion) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.health = Objects.requireNonNull(health, "health");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.defaultRegion = Objects.requireNonNull(defaultRegion, "defaultRegion");
        for (Region region : Region.values()) {
            roundRobin.put(region, new AtomicInteger());
        }

        Map<Object, Object> targets = new LinkedHashMap<>();
        nodes.all().forEach(node -> targets.put(node.id(), nodes.dataSource(node)));
        setTargetDataSources(targets);
        setDefaultTargetDataSource(nodes.dataSource(DbNode.master(defaultRegion)));
        setLenientFallback(false);
        afterPropertiesSet();
    }

    /**
     * Primary node for the current route, without advancing the round-robin counter.
     * Actual connection checkout (with fail-over) is done by {@link #getConnection()}.
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return candidates(RoutingContext.resolve(defaultRegion), false).getFirst().id();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect(null, null);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connect(username, password);
    }

    private Connection connect(String username, String password) throws SQLException {
        RoutingContext.Route route = RoutingContext.resolve(defaultRegion);
        List<DbNode> candidates = candidates(route, true);
        List<String> attempted = new ArrayList<>(candidates.size());
        SQLException lastError = null;

        for (DbNode node : candidates) {
            attempted.add(node.id());
            DataSource target = getResolvedDataSources().get(node.id());
            try {
                Connection connection = username == null
                        ? target.getConnection()
                        : target.getConnection(username, password);
                health.markUp(node);
                listener.onConnectionRouted(route, node, attempted.size() - 1);
                return connection;
            } catch (SQLException ex) {
                lastError = ex;
                health.markDown(node, ex);
                listener.onNodeFailure(route, node, ex);
                log.warn("Node {} refused a {} connection for region {}: {}",
                        node.id(), route.mode(), route.region(), ex.getMessage());
            }
        }
        throw new NodeUnavailableException(route, attempted, lastError);
    }

    /**
     * Ordered list of nodes to try for the route.
     *
     * @param advance whether to move the region's round-robin pointer
     */
    List<DbNode> candidates(RoutingContext.Route route, boolean advance) {
        DbNode master = DbNode.master(route.region());
        if (route.mode() == AccessMode.WRITE) {
            return List.of(master);
        }

        List<DbNode> replicas = nodes.replicas(route.region());
        List<DbNode> ordered = new ArrayList<>(replicas.size() + 1);
        if (!replicas.isEmpty()) {
            AtomicInteger counter = roundRobin.get(route.region());
            int start = Math.floorMod(advance ? counter.getAndIncrement() : counter.get(), replicas.size());
            for (int i = 0; i < replicas.size(); i++) {
                DbNode replica = replicas.get((start + i) % replicas.size());
                if (health.isAvailable(replica)) {
                    ordered.add(replica);
                }
            }
        }
        ordered.add(master);
        return ordered;
    }
}
