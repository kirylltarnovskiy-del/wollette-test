package com.ratelimiter.domain.algorithm;

import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.port.RateLimiterStatePort;
import java.time.Instant;

public class SlidingWindowCounterAlgorithm implements RateLimitAlgorithm {

    private final RateLimiterStatePort statePort;

    public SlidingWindowCounterAlgorithm(RateLimiterStatePort statePort) {
        this.statePort = statePort;
    }

    @Override
    public boolean allowRequest(String key, RateLimitRule rule) {
        return statePort.allowSlidingWindowCounter(key, rule, Instant.now());
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.SLIDING_WINDOW_COUNTER;
    }
}
