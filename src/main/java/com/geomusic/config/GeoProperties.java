package com.geomusic.config;

import com.geomusic.domain.Region;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Settings under the {@code geo.*} prefix, populated from environment variables in {@code application.yml}.
 *
 * @param defaultRegion region used when the request has no {@code X-User-Region} header
 * @param datasource    connection strings and pool settings of the nine nodes
 * @param health        background node probing
 * @param migration     Flyway migrations executed on every regional master
 */
@ConfigurationProperties(prefix = "geo")
public record GeoProperties(
        @DefaultValue("EURO") Region defaultRegion,
        @DefaultValue Datasource datasource,
        @DefaultValue Health health,
        @DefaultValue Migration migration) {

    /**
     * @param username default user for every node (overridable per node via a libpq URI)
     * @param password default password for every node
     * @param pool     HikariCP settings applied to every node
     * @param regions  master and replica connection strings per region
     */
    public record Datasource(
            String username,
            String password,
            @DefaultValue Pool pool,
            Map<Region, RegionNodes> regions) {

        public Datasource {
            regions = regions == null ? Map.of() : Map.copyOf(regions);
        }
    }

    public record RegionNodes(String masterUrl, List<String> replicaUrls) {

        public RegionNodes {
            replicaUrls = replicaUrls == null ? List.of() : List.copyOf(replicaUrls);
        }
    }

    /**
     * @param connectionTimeout how long a request waits for a connection before failing over to the next node
     */
    public record Pool(
            @DefaultValue("20") int masterMaxSize,
            @DefaultValue("30") int replicaMaxSize,
            @DefaultValue("2") int minIdle,
            @DefaultValue("2s") Duration connectionTimeout,
            @DefaultValue("1s") Duration validationTimeout,
            @DefaultValue("30m") Duration maxLifetime) {
    }

    public record Health(
            @DefaultValue("5s") Duration probeInterval,
            @DefaultValue("2s") Duration probeTimeout) {
    }

    /**
     * @param failOnError    abort startup if a master cannot be migrated
     * @param connectRetries Flyway connection retries per master
     */
    public record Migration(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("false") boolean failOnError,
            @DefaultValue("5") int connectRetries) {
    }
}
