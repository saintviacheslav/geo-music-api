package com.geomusic.health;

import com.geomusic.domain.Region;
import com.geomusic.routing.DbNode;
import com.geomusic.routing.GeoNodeRegistry;
import com.geomusic.routing.NodeHealthRegistry;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Exposes {@code /actuator/health} component {@code geoDatabase} with the tree region → node:
 * <pre>
 * geoDatabase
 *   ├─ amer: amer-master, amer-replica1, amer-replica2
 *   ├─ euro: ...
 *   └─ asia: ...
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class GeoHealthConfig {

    @Bean
    HealthContributor geoDatabaseHealthContributor(GeoNodeRegistry nodes, NodeHealthRegistry registry) {
        Map<String, HealthContributor> regions = new LinkedHashMap<>();
        for (Region region : Region.values()) {
            Map<String, HealthIndicator> regionNodes = new LinkedHashMap<>();
            DbNode master = nodes.master(region);
            regionNodes.put(master.id(), new NodePoolHealthIndicator(master, nodes, registry));
            for (DbNode replica : nodes.replicas(region)) {
                regionNodes.put(replica.id(), new NodePoolHealthIndicator(replica, nodes, registry));
            }
            regions.put(region.name().toLowerCase(Locale.ROOT), CompositeHealthContributor.fromMap(regionNodes));
        }
        return CompositeHealthContributor.fromMap(regions);
    }
}
