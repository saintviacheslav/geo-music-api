package com.geomusic.routing;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Normalized connection string of a node.
 *
 * <p>Accepts both JDBC URLs ({@code jdbc:postgresql://host:5432/db}) and libpq URIs
 * ({@code postgresql://user:secret@host:5432/db?sslmode=require}). Credentials embedded in a libpq URI
 * override the global {@code DB_USERNAME}/{@code DB_PASSWORD}.
 *
 * @param jdbcUrl  JDBC URL without user info
 * @param username user from the URI, {@code null} if absent
 * @param password password from the URI, {@code null} if absent
 */
public record JdbcConnectionString(String jdbcUrl, String username, String password) {

    public static JdbcConnectionString parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Connection string must not be blank");
        }
        String value = raw.trim();
        if (value.startsWith("jdbc:")) {
            return new JdbcConnectionString(value, null, null);
        }
        if (value.startsWith("postgres://") || value.startsWith("postgresql://")) {
            URI uri = URI.create(value);
            String username = null;
            String password = null;
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                username = decode(separator < 0 ? userInfo : userInfo.substring(0, separator));
                password = separator < 0 ? null : decode(userInfo.substring(separator + 1));
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return new JdbcConnectionString(
                    "jdbc:postgresql://" + uri.getHost() + ":" + port + path + query, username, password);
        }
        throw new IllegalArgumentException(
                "Unsupported connection string, expected jdbc:postgresql://... or postgresql://...: " + redact(value));
    }

    /**
     * URL safe for logs and health details.
     */
    public String redactedUrl() {
        return redact(jdbcUrl);
    }

    private static String redact(String url) {
        return url.replaceAll("(?i)(password=)[^&]*", "$1***")
                .replaceAll("://[^/@]+@", "://***@");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
