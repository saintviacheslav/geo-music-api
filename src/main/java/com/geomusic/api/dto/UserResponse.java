package com.geomusic.api.dto;

import com.geomusic.domain.Region;
import com.geomusic.domain.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String username, String email, Region homeRegion, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getHomeRegion(), user.getCreatedAt());
    }
}
