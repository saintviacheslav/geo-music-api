package com.geomusic.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Adds {@code user.region}, {@code db.target.region} and {@code db.node.type} to every HTTP server
 * observation. They end up both as attributes of the HTTP span in Tempo and as labels of the
 * {@code http.server.requests} timer in Prometheus, so controller latency can be split by region and node type.
 *
 * <p>Every request gets all three keys ({@code none} when not applicable) because Prometheus requires the
 * same label set for all series of a metric.
 */
@Component
public class GeoServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(RoutingAttributes.USER_REGION, RoutingAttributes.get(request, RoutingAttributes.USER_REGION)),
                KeyValue.of(RoutingAttributes.DB_TARGET_REGION,
                        RoutingAttributes.get(request, RoutingAttributes.DB_TARGET_REGION)),
                KeyValue.of(RoutingAttributes.DB_NODE_TYPE, RoutingAttributes.get(request, RoutingAttributes.DB_NODE_TYPE)));
    }
}
