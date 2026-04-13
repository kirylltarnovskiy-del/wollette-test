package com.ratelimiter.domain.algorithm;

import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;

public interface RateLimitAlgorithm {
    boolean allowRequest(String key, RateLimitRule rule);
    AlgorithmType type();
}
