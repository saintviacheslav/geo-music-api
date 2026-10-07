package com.geomusic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the geo-distributed music catalogue API.
 *
 * <p>The application talks to nine PostgreSQL nodes (a master and two replicas in each of the
 * AMER, EURO and ASIA regions) through a single routing {@link javax.sql.DataSource}, see
 * {@link com.geomusic.routing.GeoRoutingDataSource}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GeoMusicApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoMusicApplication.class, args);
    }
}
