package com.geomusic;

import com.geomusic.routing.GeoNodeRegistry;
import com.geomusic.routing.GeoRoutingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full context without any reachable database: pools start lazily, Hibernate does not need JDBC
 * metadata and Spring Data validates every JPQL query at startup.
 */
@SpringBootTest(properties = {
        "geo.migration.enabled=false",
        "geo.health.probe-interval=1h",
        "management.tracing.enabled=false"
})
class GeoMusicApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GeoNodeRegistry nodes;

    @Test
    void contextStartsWithNineNodesBehindRoutingDataSource() throws Exception {
        assertThat(dataSource.isWrapperFor(GeoRoutingDataSource.class) || dataSource instanceof GeoRoutingDataSource)
                .isTrue();
        assertThat(nodes.all()).hasSize(9);
    }
}
