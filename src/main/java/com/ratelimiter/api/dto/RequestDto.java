package com.ratelimiter.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RequestDto(
        @NotBlank String userId,
        @NotBlank String endpoint
) {
}
