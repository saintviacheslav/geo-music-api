package com.geomusic.api;

import com.geomusic.api.dto.FavoriteResponse;
import com.geomusic.service.FavoriteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{userId}/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * Adds the album to the user's favorites on the master of the user's home region.
     *
     * @return 201 when added, 200 when it was already a favorite
     */
    @PostMapping("/{albumId}")
    public ResponseEntity<FavoriteResponse> add(@PathVariable UUID userId, @PathVariable UUID albumId) {
        FavoriteService.FavoriteResult result = favoriteService.add(userId, albumId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.favorite());
    }
}
