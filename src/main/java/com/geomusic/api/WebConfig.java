package com.geomusic.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final UserRegionInterceptor userRegionInterceptor;

    public WebConfig(UserRegionInterceptor userRegionInterceptor) {
        this.userRegionInterceptor = userRegionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userRegionInterceptor).addPathPatterns("/api/**");
    }
}
