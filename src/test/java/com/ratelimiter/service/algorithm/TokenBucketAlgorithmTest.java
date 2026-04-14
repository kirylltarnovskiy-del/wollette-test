package com.ratelimiter.service.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitRule;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenBucketAlgorithmTest {

    @Test
    void rejectsAfterBurstCapacityConsumed() {
        TokenBucketAlgorithm algorithm = new TokenBucketAlgorithm(new InMemoryRateLimiterRepository());
        RateLimitRule rule = new RateLimitRule("per-second", Duration.ofSeconds(1), 5, 5, AlgorithmType.TOKEN_BUCKET);

        for (int i = 0; i < 5; i++) {
            assertTrue(algorithm.allowRequest("user:/request", rule));
        }
        assertFalse(algorithm.allowRequest("user:/request", rule));
    }
}
