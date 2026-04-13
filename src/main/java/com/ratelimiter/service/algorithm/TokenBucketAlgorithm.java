package com.ratelimiter.service.algorithm;

import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.repository.RedisRateLimiterRepository;
import java.time.Instant;

public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    private final RedisRateLimiterRepository rateLimiterRepository;

    public TokenBucketAlgorithm(RedisRateLimiterRepository rateLimiterRepository) {
        this.rateLimiterRepository = rateLimiterRepository;
    }

    @Override
    public boolean allowRequest(String key, RateLimitRule rule) {
        return rateLimiterRepository.allowTokenBucket(key, rule, Instant.now());
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.TOKEN_BUCKET;
    }
}
