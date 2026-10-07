package com.geomusic.service;

import com.geomusic.api.dto.AlbumResponse;
import com.geomusic.api.dto.CreateAlbumRequest;
import com.geomusic.domain.Album;
import com.geomusic.domain.Artist;
import com.geomusic.domain.repository.AlbumRepository;
import com.geomusic.domain.repository.ArtistRepository;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.GeoTransactionExecutor;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Observed(name = "geomusic.service")
public class AlbumService {

    private final AlbumRepository albums;
    private final ArtistRepository artists;
    private final GeoTransactionExecutor tx;

    public AlbumService(AlbumRepository albums, ArtistRepository artists, GeoTransactionExecutor tx) {
        this.albums = albums;
        this.artists = artists;
        this.tx = tx;
    }

    /**
     * Writes the album to the master of {@code regionCode}. The artist must already be visible on that master,
     * either because it belongs to the same shard or because it has been replicated from its origin region.
     */
    public AlbumResponse create(CreateAlbumRequest request) {
        return tx.write(request.regionCode(), () -> {
            Artist artist = artists.findById(request.artistId())
                    .orElseThrow(() -> new ResourceNotFoundException("Artist", request.artistId(),
                            "Artist %s is not available in region %s (unknown or not replicated yet)"
                                    .formatted(request.artistId(), request.regionCode())));
            Album album = albums.save(
                    new Album(artist, request.title().trim(), request.releaseYear(), request.regionCode()));
            return AlbumResponse.from(album);
        });
    }

    /**
     * Reads the album from the nearest replica (requester's region).
     */
    public AlbumResponse get(UUID id) {
        return tx.read(() -> albums.findWithArtistById(id)
                .map(AlbumResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Album", id)));
    }
}
