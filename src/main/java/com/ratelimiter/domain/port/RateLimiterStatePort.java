package com.ratelimiter.domain.port;

import com.ratelimiter.domain.model.RateLimitRule;
import java.time.Instant;

public interface RateLimiterStatePort {
    boolean allowTokenBucket(String key, RateLimitRule rule, Instant now);
    boolean allowSlidingWindowCounter(String key, RateLimitRule rule, Instant now);
}
