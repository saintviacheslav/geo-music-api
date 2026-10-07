package com.geomusic.api;

import com.geomusic.observability.RoutingAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Tells the client which node served the request: {@code X-Db-Node: euro-replica2},
 * {@code X-User-Region: EURO}. Used by the k6 scripts and handy when demonstrating fail-over with curl.
 */
@ControllerAdvice
public class RoutingHeadersAdvice implements ResponseBodyAdvice<Object> {

    public static final String DB_NODE_HEADER = "X-Db-Node";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String node = RoutingAttributes.get(httpRequest, RoutingAttributes.DB_NODE_NAME);
            String userRegion = RoutingAttributes.get(httpRequest, RoutingAttributes.USER_REGION);
            if (!RoutingAttributes.NONE.equals(node)) {
                response.getHeaders().set(DB_NODE_HEADER, node);
            }
            if (!RoutingAttributes.NONE.equals(userRegion)) {
                response.getHeaders().set(UserRegionInterceptor.REGION_HEADER, userRegion);
            }
        }
        return body;
    }
}
