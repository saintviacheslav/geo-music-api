package com.geomusic.routing;

import com.geomusic.domain.Region;

import java.util.Objects;
import java.util.Optional;

/**
 * Thread-bound routing information consumed by {@link GeoRoutingDataSource}.
 *
 * <p>Two layers are kept:
 * <ul>
 *   <li><b>request binding</b> — the user's region (from {@code X-User-Region}) and the default access mode
 *       derived from the HTTP method; set once per request by the web layer;</li>
 *   <li><b>explicit route</b> — set by {@link GeoTransactionExecutor} around a transaction when the service knows
 *       better, e.g. a write that must go to the master of the region owning the object.</li>
 * </ul>
 * The explicit route wins over the request binding.
 */
public final class RoutingContext {

    /**
     * Where a unit of work should be executed.
     */
    public record Route(Region region, AccessMode mode) {
        public Route {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * Restores the previous explicit route on close.
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record RequestBinding(Region userRegion, AccessMode defaultMode) {
    }

    private static final ThreadLocal<RequestBinding> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Route> EXPLICIT = new ThreadLocal<>();

    private RoutingContext() {
    }

    public static void bindRequest(Region userRegion, AccessMode defaultMode) {
        REQUEST.set(new RequestBinding(Objects.requireNonNull(userRegion), Objects.requireNonNull(defaultMode)));
    }

    public static void clear() {
        REQUEST.remove();
        EXPLICIT.remove();
    }

    public static Optional<Region> userRegion() {
        RequestBinding binding = REQUEST.get();
        return binding == null ? Optional.empty() : Optional.of(binding.userRegion());
    }

    public static Scope use(Route route) {
        Objects.requireNonNull(route, "route");
        Route previous = EXPLICIT.get();
        EXPLICIT.set(route);
        return () -> {
            if (previous == null) {
                EXPLICIT.remove();
            } else {
                EXPLICIT.set(previous);
            }
        };
    }

    /**
     * Resolves the effective route. Outside of any request (startup, background jobs) work goes to the
     * master of {@code fallbackRegion}.
     */
    public static Route resolve(Region fallbackRegion) {
        Route explicit = EXPLICIT.get();
        if (explicit != null) {
            return explicit;
        }
        RequestBinding binding = REQUEST.get();
        if (binding != null) {
            return new Route(binding.userRegion(), binding.defaultMode());
        }
        return new Route(fallbackRegion, AccessMode.WRITE);
    }
}
