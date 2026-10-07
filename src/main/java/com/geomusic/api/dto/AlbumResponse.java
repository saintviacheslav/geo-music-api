package com.geomusic.api.dto;

import com.geomusic.domain.Album;
import com.geomusic.domain.Region;

import java.time.Instant;
import java.util.UUID;

public record AlbumResponse(
        UUID id,
        String title,
        Integer releaseYear,
        Region regionCode,
        ArtistResponse artist,
        Instant createdAt) {

    /**
     * Must be called inside the transaction: the artist association is lazy.
     */
    public static AlbumResponse from(Album album) {
        return new AlbumResponse(album.getId(), album.getTitle(), album.getReleaseYear(), album.getRegionCode(),
                ArtistResponse.from(album.getArtist()), album.getCreatedAt());
    }
}
