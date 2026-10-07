package com.geomusic.routing;

import com.geomusic.domain.Region;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds one connection pool per PostgreSQL node and answers topology questions
 * ("who is the master of ASIA?", "which replicas does EURO have?").
 */
public class GeoNodeRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GeoNodeRegistry.class);

    private final Map<DbNode, DataSource> dataSources;
    private final Map<DbNode, String> urls;
    private final Map<Region, List<DbNode>> replicasByRegion = new EnumMap<>(Region.class);

    public GeoNodeRegistry(Map<DbNode, ? extends DataSource> dataSources) {
        this(dataSources, Map.of());
    }

    /**
     * @param dataSources pool per node; must contain a master for every {@link Region}
     * @param urls        connection URLs without credentials, used for diagnostics only
     */
    public GeoNodeRegistry(Map<DbNode, ? extends DataSource> dataSources, Map<DbNode, String> urls) {
        this.dataSources = Collections.unmodifiableMap(new LinkedHashMap<>(dataSources));
        this.urls = Map.copyOf(urls);
        for (Region region : Region.values()) {
            if (!this.dataSources.containsKey(DbNode.master(region))) {
                throw new IllegalArgumentException("No master node configured for region " + region);
            }
            List<DbNode> replicas = new ArrayList<>();
            this.dataSources.keySet().stream()
                    .filter(node -> node.region() == region && node.type() == NodeType.REPLICA)
                    .forEach(replicas::add);
            replicasByRegion.put(region, List.copyOf(replicas));
        }
    }

    public List<DbNode> all() {
        return List.copyOf(dataSources.keySet());
    }

    public DbNode master(Region region) {
        return DbNode.master(region);
    }

    public List<DbNode> replicas(Region region) {
        return replicasByRegion.get(region);
    }

    public DataSource dataSource(DbNode node) {
        DataSource dataSource = dataSources.get(node);
        if (dataSource == null) {
            throw new IllegalArgumentException("Unknown database node " + node.id());
        }
        return dataSource;
    }

    public String url(DbNode node) {
        return urls.getOrDefault(node, "n/a");
    }

    /**
     * Live pool statistics when the node is backed by HikariCP.
     */
    public Optional<HikariPoolMXBean> pool(DbNode node) {
        return dataSources.get(node) instanceof HikariDataSource hikari
                ? Optional.ofNullable(hikari.getHikariPoolMXBean())
                : Optional.empty();
    }

    @Override
    public void close() {
        dataSources.forEach((node, dataSource) -> {
            if (dataSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ex) {
                    log.warn("Failed to close pool of node {}", node.id(), ex);
                }
            }
        });
    }
}
