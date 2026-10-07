package com.geomusic.api.dto;

import com.geomusic.domain.Artist;
import com.geomusic.domain.Region;

import java.util.UUID;

public record ArtistResponse(UUID id, String name, Region originRegion, String genre) {

    public static ArtistResponse from(Artist artist) {
        return new ArtistResponse(artist.getId(), artist.getName(), artist.getOriginRegion(), artist.getGenre());
    }
}
