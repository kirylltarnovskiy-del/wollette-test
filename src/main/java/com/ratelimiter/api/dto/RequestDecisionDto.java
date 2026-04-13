package com.ratelimiter.api.dto;

public record RequestDecisionDto(boolean allowed, String userId, String endpoint) {
}
