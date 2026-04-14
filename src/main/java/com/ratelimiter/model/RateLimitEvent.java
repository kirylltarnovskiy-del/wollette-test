package com.ratelimiter.model;

import java.time.Instant;

public record RateLimitEvent(
        String userId,
        String endpoint,
        boolean allowed,
        UserTier tier,
        Instant timestamp
) {
}
