package com.ratelimiter.dto;

public record RequestDecisionDto(boolean allowed, String userId, String endpoint) {
}
