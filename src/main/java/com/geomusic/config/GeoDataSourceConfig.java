package com.geomusic.config;

import com.geomusic.domain.Region;
import com.geomusic.routing.DbNode;
import com.geomusic.routing.GeoNodeRegistry;
import com.geomusic.routing.GeoRoutingDataSource;
import com.geomusic.routing.GeoTransactionExecutor;
import com.geomusic.routing.JdbcConnectionString;
import com.geomusic.routing.NodeHealthRegistry;
import com.geomusic.routing.NodeType;
import com.geomusic.routing.RoutingListener;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one HikariCP pool per node and exposes them to JPA through a single {@link GeoRoutingDataSource}.
 *
 * <p>Pools start even if their node is down ({@code initializationFailTimeout = -1}); availability is handled
 * at runtime by fail-over and health probing.
 */
@Configuration(proxyBeanMethods = false)
public class GeoDataSourceConfig {

    @Bean(destroyMethod = "close")
    GeoNodeRegistry geoNodeRegistry(GeoProperties properties, MeterRegistry meterRegistry) {
        GeoProperties.Datasource settings = properties.datasource();
        Map<DbNode, DataSource> pools = new LinkedHashMap<>();
        Map<DbNode, String> urls = new LinkedHashMap<>();

        for (Region region : Region.values()) {
            GeoProperties.RegionNodes regionNodes = settings.regions().get(region);
            if (regionNodes == null || !StringUtils.hasText(regionNodes.masterUrl())) {
                throw new IllegalStateException(
                        "Master connection string for region %s is missing (env %s_MASTER_URL)".formatted(region, region));
            }
            List<String> replicaUrls = regionNodes.replicaUrls().stream().filter(StringUtils::hasText).toList();
            if (replicaUrls.isEmpty()) {
                throw new IllegalStateException(
                        "No replica connection strings for region %s (env %s_REPLICA1_URL, %s_REPLICA2_URL)"
                                .formatted(region, region, region));
            }

            register(DbNode.master(region), regionNodes.masterUrl(), settings, meterRegistry, pools, urls);
            for (int i = 0; i < replicaUrls.size(); i++) {
                register(DbNode.replica(region, i + 1), replicaUrls.get(i), settings, meterRegistry, pools, urls);
            }
        }
        return new GeoNodeRegistry(pools, urls);
    }

    @Bean
    NodeHealthRegistry nodeHealthRegistry(GeoNodeRegistry nodes) {
        return new NodeHealthRegistry(nodes.all());
    }

    @Bean
    @Primary
    DataSource dataSource(GeoNodeRegistry nodes, NodeHealthRegistry health,
                          RoutingListener routingListener, GeoProperties properties) {
        return new GeoRoutingDataSource(nodes, health, routingListener, properties.defaultRegion());
    }

    @Bean
    GeoTransactionExecutor geoTransactionExecutor(PlatformTransactionManager transactionManager,
                                                  GeoProperties properties) {
        return new GeoTransactionExecutor(transactionManager, properties.defaultRegion());
    }

    private static void register(DbNode node, String connectionString, GeoProperties.Datasource settings,
                                 MeterRegistry meterRegistry, Map<DbNode, DataSource> pools, Map<DbNode, String> urls) {
        JdbcConnectionString target = JdbcConnectionString.parse(connectionString);
        pools.put(node, createPool(node, target, settings, meterRegistry));
        urls.put(node, target.redactedUrl());
    }

    private static HikariDataSource createPool(DbNode node, JdbcConnectionString target,
                                               GeoProperties.Datasource settings, MeterRegistry meterRegistry) {
        GeoProperties.Pool pool = settings.pool();
        int maxSize = node.type() == NodeType.MASTER ? pool.masterMaxSize() : pool.replicaMaxSize();

        HikariConfig config = new HikariConfig();
        config.setPoolName("geo-" + node.id());
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(target.username() != null ? target.username() : settings.username());
        config.setPassword(target.password() != null ? target.password() : settings.password());
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(Math.min(pool.minIdle(), maxSize));
        config.setConnectionTimeout(pool.connectionTimeout().toMillis());
        config.setValidationTimeout(pool.validationTimeout().toMillis());
        config.setMaxLifetime(pool.maxLifetime().toMillis());
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(false);
        config.setReadOnly(node.type() == NodeType.REPLICA);
        config.addDataSourceProperty("ApplicationName", "geo-music-api/" + node.id());
        config.addDataSourceProperty("connectTimeout", String.valueOf(Math.max(1, pool.connectionTimeout().toSeconds())));
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        return new HikariDataSource(config);
    }
}
