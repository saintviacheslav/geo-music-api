package com.geomusic.api.dto;

import com.geomusic.domain.Region;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull Region homeRegion) {
}
