package com.geomusic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * "User liked an album" link. Stored in the shard of the user's home region ({@link #userRegion}).
 *
 * <p>Implements {@link Persistable} because the key is assigned by the application: without it
 * {@code repository.save()} would issue a {@code SELECT} before every {@code INSERT}.
 */
@Entity
@Table(name = "user_favorite_albums")
public class UserFavoriteAlbum implements Persistable<UserFavoriteAlbumId> {

    @EmbeddedId
    private UserFavoriteAlbumId id;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_region", nullable = false, length = 4)
    private Region userRegion;

    @Transient
    private boolean isNew = true;

    protected UserFavoriteAlbum() {
    }

    public UserFavoriteAlbum(UserFavoriteAlbumId id, Region userRegion) {
        this.id = id;
        this.userRegion = userRegion;
        this.addedAt = Instant.now();
    }

    @Override
    public UserFavoriteAlbumId getId() {
        return id;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Region getUserRegion() {
        return userRegion;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
