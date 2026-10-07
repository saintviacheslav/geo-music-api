package com.geomusic.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConnectionStringTest {

    @Test
    void keepsJdbcUrlAsIs() {
        JdbcConnectionString parsed = JdbcConnectionString.parse("jdbc:postgresql://amer-master:5432/geomusic");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://amer-master:5432/geomusic");
        assertThat(parsed.username()).isNull();
        assertThat(parsed.password()).isNull();
    }

    @Test
    void convertsLibpqUriAndExtractsCredentials() {
        JdbcConnectionString parsed =
                JdbcConnectionString.parse("postgresql://geo:p%40ss@euro-replica1:6422/geomusic?sslmode=disable");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://euro-replica1:6422/geomusic?sslmode=disable");
        assertThat(parsed.username()).isEqualTo("geo");
        assertThat(parsed.password()).isEqualTo("p@ss");
    }

    @Test
    void defaultsPortAndRedactsPassword() {
        JdbcConnectionString parsed = JdbcConnectionString.parse("postgres://asia-master/geomusic");
        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://asia-master:5432/geomusic");

        JdbcConnectionString withPassword =
                JdbcConnectionString.parse("jdbc:postgresql://h:5432/db?user=geo&password=secret");
        assertThat(withPassword.redactedUrl()).isEqualTo("jdbc:postgresql://h:5432/db?user=geo&password=***");
    }

    @Test
    void rejectsUnsupportedFormats() {
        assertThatThrownBy(() -> JdbcConnectionString.parse("mysql://h/db"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcConnectionString.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
