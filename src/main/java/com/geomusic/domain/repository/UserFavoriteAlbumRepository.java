package com.geomusic.domain.repository;

import com.geomusic.domain.UserFavoriteAlbum;
import com.geomusic.domain.UserFavoriteAlbumId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserFavoriteAlbumRepository extends JpaRepository<UserFavoriteAlbum, UserFavoriteAlbumId> {

    @Query("""
            select f.id.albumId from UserFavoriteAlbum f
            where f.id.userId = :userId
            order by f.addedAt desc""")
    List<UUID> findRecentAlbumIds(@Param("userId") UUID userId, Pageable page);
}
