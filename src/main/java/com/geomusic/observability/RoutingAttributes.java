package com.geomusic.observability;

import com.geomusic.domain.Region;
import com.geomusic.routing.DbNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Attribute names shared by traces, metrics and response headers, and helpers to keep the routing
 * outcome of the current HTTP request in request attributes.
 *
 * <p>When a request touches several nodes (e.g. replica lookup followed by a master write) the last
 * node wins, which is the one that served the main operation.
 */
public final class RoutingAttributes {

    public static final String USER_REGION = "user.region";
    public static final String DB_TARGET_REGION = "db.target.region";
    public static final String DB_NODE_TYPE = "db.node.type";
    public static final String DB_NODE_NAME = "db.node.name";
    public static final String NONE = "none";

    private static final String PREFIX = RoutingAttributes.class.getName() + ".";

    private RoutingAttributes() {
    }

    public static void recordUserRegion(HttpServletRequest request, Region region) {
        request.setAttribute(PREFIX + USER_REGION, region.name());
    }

    /**
     * Stores the node in the current request, if any (no-op on background threads).
     */
    public static void recordNode(DbNode node) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        attributes.setAttribute(PREFIX + DB_TARGET_REGION, node.region().name(), RequestAttributes.SCOPE_REQUEST);
        attributes.setAttribute(PREFIX + DB_NODE_TYPE, node.type().name(), RequestAttributes.SCOPE_REQUEST);
        attributes.setAttribute(PREFIX + DB_NODE_NAME, node.id(), RequestAttributes.SCOPE_REQUEST);
    }

    /**
     * @return the recorded value or {@link #NONE}
     */
    public static String get(HttpServletRequest request, String key) {
        Object value = request == null ? null : request.getAttribute(PREFIX + key);
        return value == null ? NONE : value.toString();
    }
}
