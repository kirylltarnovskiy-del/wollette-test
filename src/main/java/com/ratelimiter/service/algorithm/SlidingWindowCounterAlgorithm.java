package com.ratelimiter.service.algorithm;

import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.repository.RedisRateLimiterRepository;
import java.time.Instant;

public class SlidingWindowCounterAlgorithm implements RateLimitAlgorithm {

    private final RedisRateLimiterRepository rateLimiterRepository;

    public SlidingWindowCounterAlgorithm(RedisRateLimiterRepository rateLimiterRepository) {
        this.rateLimiterRepository = rateLimiterRepository;
    }

    @Override
    public boolean allowRequest(String key, RateLimitRule rule) {
        return rateLimiterRepository.allowSlidingWindowCounter(key, rule, Instant.now());
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.SLIDING_WINDOW_COUNTER;
    }
}
