package com.geomusic.routing;

import com.geomusic.domain.Region;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs a unit of work in a transaction on the right node.
 *
 * <p>The route is bound <em>before</em> the transaction opens its connection, which is the only moment
 * {@link GeoRoutingDataSource} looks at it. For the same reason these methods must not be nested inside
 * an already running transaction: the connection would already be bound to another node.
 */
public class GeoTransactionExecutor {

    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;
    private final Region defaultRegion;

    public GeoTransactionExecutor(PlatformTransactionManager transactionManager, Region defaultRegion) {
        this.readWrite = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.defaultRegion = Objects.requireNonNull(defaultRegion, "defaultRegion");
    }

    /**
     * Region of the current user ({@code X-User-Region}), or the configured default.
     */
    public Region currentUserRegion() {
        return RoutingContext.userRegion().orElse(defaultRegion);
    }

    /**
     * Read-write transaction on the master of {@code shardRegion} — the region that owns the data.
     */
    public <T> T write(Region shardRegion, Supplier<T> work) {
        return execute(new RoutingContext.Route(shardRegion, AccessMode.WRITE), readWrite, work);
    }

    /**
     * Read-only transaction on a replica of the current user's region (nearest replica).
     */
    public <T> T read(Supplier<T> work) {
        return readFrom(currentUserRegion(), work);
    }

    /**
     * Read-only transaction on a replica of the given region, falling back to its master.
     */
    public <T> T readFrom(Region region, Supplier<T> work) {
        return execute(new RoutingContext.Route(region, AccessMode.READ), readOnly, work);
    }

    /**
     * Read-only transaction on the master of the region, for lookups that must not suffer replication lag.
     */
    public <T> T readFromMaster(Region region, Supplier<T> work) {
        return execute(new RoutingContext.Route(region, AccessMode.WRITE), readOnly, work);
    }

    private <T> T execute(RoutingContext.Route route, TransactionTemplate template, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Geo-routed work must start outside of a transaction, the connection is already bound");
        }
        try (RoutingContext.Scope ignored = RoutingContext.use(route)) {
            return template.execute(status -> work.get());
        }
    }
}
