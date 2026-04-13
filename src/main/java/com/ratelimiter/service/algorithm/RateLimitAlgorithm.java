package com.ratelimiter.service.algorithm;

import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitRule;

public interface RateLimitAlgorithm {
    boolean allowRequest(String key, RateLimitRule rule);
    AlgorithmType type();
}
