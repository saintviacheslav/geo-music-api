package com.geomusic.api;

import com.geomusic.config.GeoProperties;
import com.geomusic.domain.Region;
import com.geomusic.error.InvalidRegionException;
import com.geomusic.observability.RoutingAttributes;
import com.geomusic.routing.AccessMode;
import com.geomusic.routing.RoutingContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Binds the request to the user's region ({@code X-User-Region}, default from {@code geo.default-region})
 * and to the default access mode derived from the HTTP method: GET reads from replicas,
 * POST/PUT/DELETE write to a master.
 */
@Component
public class UserRegionInterceptor implements HandlerInterceptor {

    public static final String REGION_HEADER = "X-User-Region";

    private final Region defaultRegion;

    public UserRegionInterceptor(GeoProperties properties) {
        this.defaultRegion = properties.defaultRegion();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(REGION_HEADER);
        Region region = header == null || header.isBlank()
                ? defaultRegion
                : Region.parse(header).orElseThrow(() -> new InvalidRegionException(header));

        RoutingContext.bindRequest(region, AccessMode.forHttpMethod(request.getMethod()));
        RoutingAttributes.recordUserRegion(request, region);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RoutingContext.clear();
    }
}
