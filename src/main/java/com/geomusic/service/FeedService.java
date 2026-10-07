package com.geomusic.service;

import com.geomusic.api.dto.AlbumResponse;
import com.geomusic.api.dto.FeedResponse;
import com.geomusic.domain.Album;
import com.geomusic.domain.Region;
import com.geomusic.domain.User;
import com.geomusic.domain.repository.AlbumRepository;
import com.geomusic.domain.repository.UserFavoriteAlbumRepository;
import com.geomusic.domain.repository.UserRepository;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.GeoTransactionExecutor;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Observed(name = "geomusic.service")
public class FeedService {

    private final UserRepository users;
    private final AlbumRepository albums;
    private final UserFavoriteAlbumRepository favorites;
    private final GeoTransactionExecutor tx;

    public FeedService(UserRepository users, AlbumRepository albums,
                       UserFavoriteAlbumRepository favorites, GeoTransactionExecutor tx) {
        this.users = users;
        this.albums = albums;
        this.favorites = favorites;
        this.tx = tx;
    }

    /**
     * Builds the feed entirely from the nearest replica (requester's region), which holds the replicated data
     * of all shards:
     * <ol>
     *   <li>the user's favorite albums, newest first;</li>
     *   <li>recommendations — latest albums of the genres found in favorites, or, for a user without favorites,
     *       the latest albums of the user's home region.</li>
     * </ol>
     */
    public FeedResponse feed(UUID userId, int limit) {
        Region servedFrom = tx.currentUserRegion();
        Pageable page = PageRequest.of(0, limit);

        return tx.read(() -> {
            User user = users.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));

            List<UUID> favoriteIds = favorites.findRecentAlbumIds(userId, page);
            List<Album> favoriteAlbums = favoriteIds.isEmpty()
                    ? List.of()
                    : inOrder(albums.findAllWithArtistByIdIn(favoriteIds), favoriteIds);

            Set<String> genres = favoriteAlbums.stream()
                    .map(album -> album.getArtist().getGenre())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Album> recommendations = genres.isEmpty()
                    ? albums.findLatestInRegion(user.getHomeRegion(), page)
                    : albums.findRecommendations(genres, favoriteIds, page);

            return new FeedResponse(user.getId(), user.getHomeRegion(), servedFrom,
                    favoriteAlbums.stream().map(AlbumResponse::from).toList(),
                    recommendations.stream().map(AlbumResponse::from).toList(),
                    Instant.now());
        });
    }

    private static List<Album> inOrder(List<Album> loaded, List<UUID> orderedIds) {
        Map<UUID, Integer> position = new HashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            position.put(orderedIds.get(i), i);
        }
        return loaded.stream()
                .sorted(Comparator.comparing((Album album) -> position.get(album.getId())))
                .toList();
    }
}
