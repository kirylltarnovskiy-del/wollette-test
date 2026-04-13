package com.ratelimiter.domain.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SlidingWindowCounterAlgorithmTest {

    @Test
    void rejectsWhenLimitExceeded() {
        SlidingWindowCounterAlgorithm algorithm = new SlidingWindowCounterAlgorithm(new InMemoryRateLimiterStatePort());
        RateLimitRule rule = new RateLimitRule("per-minute", Duration.ofMinutes(1), 3, 3, AlgorithmType.SLIDING_WINDOW_COUNTER);

        assertTrue(algorithm.allowRequest("user:/request", rule));
        assertTrue(algorithm.allowRequest("user:/request", rule));
        assertTrue(algorithm.allowRequest("user:/request", rule));
        assertFalse(algorithm.allowRequest("user:/request", rule));
    }
}
