package com.geomusic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key of {@link UserFavoriteAlbum}.
 */
@Embeddable
public class UserFavoriteAlbumId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "album_id", nullable = false)
    private UUID albumId;

    protected UserFavoriteAlbumId() {
    }

    public UserFavoriteAlbumId(UUID userId, UUID albumId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.albumId = Objects.requireNonNull(albumId, "albumId");
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAlbumId() {
        return albumId;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UserFavoriteAlbumId other
                && userId.equals(other.userId)
                && albumId.equals(other.albumId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, albumId);
    }
}
