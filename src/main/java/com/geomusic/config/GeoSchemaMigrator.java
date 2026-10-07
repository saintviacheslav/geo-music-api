package com.geomusic.config;

import com.geomusic.domain.Region;
import com.geomusic.routing.DbNode;
import com.geomusic.routing.GeoNodeRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Applies Flyway migrations to every regional master in parallel before the web server starts.
 *
 * <p>Spring Boot's single-DataSource Flyway auto-configuration is disabled: each master hosts its own copy of
 * the schema (DDL is not carried by logical replication), and replicas receive it through streaming
 * replication. Masters sharing the same URL (centralized setup) are migrated once.
 */
@Component
public class GeoSchemaMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(GeoSchemaMigrator.class);
    private static final int CONNECT_RETRIES_MAX_INTERVAL_SECONDS = 5;

    private final GeoNodeRegistry nodes;
    private final GeoProperties.Migration settings;

    public GeoSchemaMigrator(GeoNodeRegistry nodes, GeoProperties properties) {
        this.nodes = nodes;
        this.settings = properties.migration();
    }

    @Override
    public void afterPropertiesSet() {
        if (!settings.enabled()) {
            log.info("Schema migration is disabled (geo.migration.enabled=false)");
            return;
        }

        Map<String, DbNode> mastersByUrl = new LinkedHashMap<>();
        for (Region region : Region.values()) {
            DbNode master = nodes.master(region);
            mastersByUrl.putIfAbsent(nodes.url(master), master);
        }

        List<String> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<DbNode, Future<MigrateResult>> results = new LinkedHashMap<>();
            mastersByUrl.values().forEach(master -> results.put(master, executor.submit(() -> migrate(master))));

            for (Map.Entry<DbNode, Future<MigrateResult>> entry : results.entrySet()) {
                try {
                    MigrateResult result = entry.getValue().get();
                    log.info("Flyway on {}: {} migration(s) applied, schema version {}",
                            entry.getKey().id(), result.migrationsExecuted, result.targetSchemaVersion);
                } catch (ExecutionException ex) {
                    failures.add(entry.getKey().id());
                    log.error("Flyway migration failed on {}", entry.getKey().id(), ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while migrating database schema", ex);
                }
            }
        }

        if (!failures.isEmpty() && settings.failOnError()) {
            throw new IllegalStateException("Schema migration failed on " + String.join(", ", failures));
        }
    }

    private MigrateResult migrate(DbNode master) {
        return Flyway.configure()
                .dataSource(nodes.dataSource(master))
                .locations("classpath:db/migration")
                .connectRetries(settings.connectRetries())
                .connectRetriesInterval(CONNECT_RETRIES_MAX_INTERVAL_SECONDS)
                .load()
                .migrate();
    }
}
