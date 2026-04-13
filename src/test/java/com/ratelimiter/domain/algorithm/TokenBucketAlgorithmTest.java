package com.ratelimiter.domain.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenBucketAlgorithmTest {

    @Test
    void rejectsAfterBurstCapacityConsumed() {
        TokenBucketAlgorithm algorithm = new TokenBucketAlgorithm(new InMemoryRateLimiterStatePort());
        RateLimitRule rule = new RateLimitRule("per-second", Duration.ofSeconds(1), 5, 5, AlgorithmType.TOKEN_BUCKET);

        for (int i = 0; i < 5; i++) {
            assertTrue(algorithm.allowRequest("user:/request", rule));
        }
        assertFalse(algorithm.allowRequest("user:/request", rule));
    }
}
