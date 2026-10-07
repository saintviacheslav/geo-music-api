package com.geomusic.observability;

import com.geomusic.routing.DbNode;
import com.geomusic.routing.RoutingContext;
import com.geomusic.routing.RoutingListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * Publishes routing decisions:
 * <ul>
 *   <li>counter {@code geo.db.routing} — connections per node, mode and fail-over flag;</li>
 *   <li>counter {@code geo.db.node.failures} — refused connections per node;</li>
 *   <li>attributes {@code db.target.region}, {@code db.node.type}, {@code db.node.name}, {@code user.region}
 *       on the current span (the JDBC connection span, or the HTTP span when JDBC tracing is off);</li>
 *   <li>request attributes picked up by {@link GeoServerRequestObservationConvention} for the HTTP span.</li>
 * </ul>
 */
@Component
public class RoutingObservability implements RoutingListener {

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<Tracer> tracer;

    public RoutingObservability(MeterRegistry meterRegistry, ObjectProvider<Tracer> tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @Override
    public void onConnectionRouted(RoutingContext.Route route, DbNode node, int failedAttempts) {
        boolean failover = failedAttempts > 0;
        Counter.builder("geo.db.routing")
                .description("Connections handed out by the geo routing datasource")
                .tag("mode", route.mode().name())
                .tag("region", node.region().name())
                .tag("node", node.id())
                .tag("node.type", node.type().name())
                .tag("failover", String.valueOf(failover))
                .register(meterRegistry)
                .increment();

        Span span = currentSpan();
        if (span != null) {
            span.tag(RoutingAttributes.DB_TARGET_REGION, node.region().name());
            span.tag(RoutingAttributes.DB_NODE_TYPE, node.type().name());
            span.tag(RoutingAttributes.DB_NODE_NAME, node.id());
            RoutingContext.userRegion().ifPresent(region -> span.tag(RoutingAttributes.USER_REGION, region.name()));
            if (failover) {
                span.event("db.failover");
            }
        }
        RoutingAttributes.recordNode(node);
    }

    @Override
    public void onNodeFailure(RoutingContext.Route route, DbNode node, SQLException error) {
        Counter.builder("geo.db.node.failures")
                .description("Connection attempts refused by a database node")
                .tag("mode", route.mode().name())
                .tag("region", node.region().name())
                .tag("node", node.id())
                .tag("node.type", node.type().name())
                .register(meterRegistry)
                .increment();

        Span span = currentSpan();
        if (span != null) {
            span.event("db.node.unavailable " + node.id());
        }
    }

    private Span currentSpan() {
        Tracer current = tracer.getIfAvailable();
        return current == null ? null : current.currentSpan();
    }
}
