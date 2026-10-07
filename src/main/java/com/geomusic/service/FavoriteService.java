package com.geomusic.service;

import com.geomusic.api.dto.FavoriteResponse;
import com.geomusic.domain.Region;
import com.geomusic.domain.User;
import com.geomusic.domain.UserFavoriteAlbum;
import com.geomusic.domain.UserFavoriteAlbumId;
import com.geomusic.domain.repository.AlbumRepository;
import com.geomusic.domain.repository.UserFavoriteAlbumRepository;
import com.geomusic.domain.repository.UserRepository;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.GeoTransactionExecutor;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Observed(name = "geomusic.service")
public class FavoriteService {

    /**
     * @param created {@code false} when the album was already in the user's favorites
     */
    public record FavoriteResult(FavoriteResponse favorite, boolean created) {
    }

    private final UserRepository users;
    private final AlbumRepository albums;
    private final UserFavoriteAlbumRepository favorites;
    private final UserHomeRegionResolver homeRegions;
    private final GeoTransactionExecutor tx;

    public FavoriteService(UserRepository users, AlbumRepository albums, UserFavoriteAlbumRepository favorites,
                           UserHomeRegionResolver homeRegions, GeoTransactionExecutor tx) {
        this.users = users;
        this.albums = albums;
        this.favorites = favorites;
        this.homeRegions = homeRegions;
        this.tx = tx;
    }

    /**
     * Idempotently adds the album to the user's favorites on the master of the user's home region.
     */
    public FavoriteResult add(UUID userId, UUID albumId) {
        Region homeRegion = homeRegions.resolve(userId);
        return tx.write(homeRegion, () -> {
            User user = users.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));
            if (!albums.existsById(albumId)) {
                throw new ResourceNotFoundException("Album", albumId,
                        "Album %s is not available in region %s (unknown or not replicated yet)"
                                .formatted(albumId, homeRegion));
            }
            UserFavoriteAlbumId id = new UserFavoriteAlbumId(userId, albumId);
            return favorites.findById(id)
                    .map(existing -> new FavoriteResult(FavoriteResponse.from(existing), false))
                    .orElseGet(() -> new FavoriteResult(FavoriteResponse.from(
                            favorites.save(new UserFavoriteAlbum(id, user.getHomeRegion()))), true));
        });
    }
}
