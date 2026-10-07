package com.geomusic.api.dto;

import com.geomusic.domain.Region;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Album feed of a user.
 *
 * @param servedFromRegion region whose replicas served the feed (the requester's region)
 * @param favorites        albums the user liked, newest first
 * @param recommendations  latest albums of the genres the user likes, or of the user's home region
 */
public record FeedResponse(
        UUID userId,
        Region homeRegion,
        Region servedFromRegion,
        List<AlbumResponse> favorites,
        List<AlbumResponse> recommendations,
        Instant generatedAt) {
}
