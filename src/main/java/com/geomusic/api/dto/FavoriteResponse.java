package com.geomusic.api.dto;

import com.geomusic.domain.Region;
import com.geomusic.domain.UserFavoriteAlbum;

import java.time.Instant;
import java.util.UUID;

public record FavoriteResponse(UUID userId, UUID albumId, Region userRegion, Instant addedAt) {

    public static FavoriteResponse from(UserFavoriteAlbum favorite) {
        return new FavoriteResponse(favorite.getId().getUserId(), favorite.getId().getAlbumId(),
                favorite.getUserRegion(), favorite.getAddedAt());
    }
}
