package com.geomusic.api.dto;

import com.geomusic.domain.Region;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateArtistRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull Region originRegion,
        @NotBlank @Size(max = 100) String genre) {
}
