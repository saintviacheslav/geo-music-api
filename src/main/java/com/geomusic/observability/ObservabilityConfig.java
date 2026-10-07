package com.geomusic.observability;

import net.ttddyy.observation.boot.autoconfigure.DataSourceNameResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * The default resolver opens a connection at startup to read the JDBC URL, which would hit whatever node
     * the routing datasource picks without a request context. The actual node is reported per connection
     * by {@link RoutingObservability}.
     */
    @Bean
    DataSourceNameResolver dataSourceNameResolver() {
        return (beanName, dataSource) -> "geo-routing";
    }
}
