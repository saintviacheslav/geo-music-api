package com.geomusic.service;

import com.geomusic.api.dto.ArtistResponse;
import com.geomusic.api.dto.CreateArtistRequest;
import com.geomusic.domain.Artist;
import com.geomusic.domain.repository.ArtistRepository;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.GeoTransactionExecutor;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Observed(name = "geomusic.service")
public class ArtistService {

    private final ArtistRepository artists;
    private final GeoTransactionExecutor tx;

    public ArtistService(ArtistRepository artists, GeoTransactionExecutor tx) {
        this.artists = artists;
        this.tx = tx;
    }

    /**
     * Writes the artist to the master of its origin region.
     */
    public ArtistResponse create(CreateArtistRequest request) {
        return tx.write(request.originRegion(), () -> ArtistResponse.from(
                artists.save(new Artist(request.name().trim(), request.originRegion(), request.genre().trim()))));
    }

    public ArtistResponse get(UUID id) {
        return tx.read(() -> artists.findById(id)
                .map(ArtistResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Artist", id)));
    }
}
