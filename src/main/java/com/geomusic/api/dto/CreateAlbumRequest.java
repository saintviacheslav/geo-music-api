package com.geomusic.api.dto;

import com.geomusic.domain.Region;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAlbumRequest(
        @NotNull UUID artistId,
        @NotBlank @Size(max = 300) String title,
        @Min(1900) @Max(2100) Integer releaseYear,
        @NotNull Region regionCode) {
}
