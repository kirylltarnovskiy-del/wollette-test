package com.ratelimiter.model;

import java.time.Duration;

public record RateLimitRule(
        String name,
        Duration window,
        long maxRequests,
        long burstCapacity,
        AlgorithmType algorithm
) {
}
