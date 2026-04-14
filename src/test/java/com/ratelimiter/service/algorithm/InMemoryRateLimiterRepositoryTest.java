package com.ratelimiter.service.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitRule;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterRepositoryTest {

    private static final RateLimitRule TOKEN_RULE = new RateLimitRule(
            "token-rule",
            Duration.ofSeconds(10),
            10,
            10,
            AlgorithmType.TOKEN_BUCKET
    );

    private static final RateLimitRule WINDOW_RULE = new RateLimitRule(
            "window-rule",
            Duration.ofSeconds(10),
            10,
            10,
            AlgorithmType.SLIDING_WINDOW_COUNTER
    );

    @Test
    void tokenBucketRejectsAfterBurstCapacityIsConsumed() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();
        Instant now = Instant.ofEpochMilli(0);

        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, now));
        }
        assertFalse(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, now));
    }

    @Test
    void tokenBucketRefillsBasedOnElapsedTime() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();
        Instant t0 = Instant.ofEpochMilli(0);

        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, t0));
        }
        assertFalse(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, t0));

        Instant tPlus3Sec = Instant.ofEpochMilli(3_000);
        assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
        assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
        assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
        assertFalse(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
    }

    @Test
    void tokenBucketRefillNeverExceedsBurstCapacity() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();
        Instant t0 = Instant.ofEpochMilli(0);

        assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, t0));

        Instant muchLater = Instant.ofEpochMilli(1_000_000);
        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, muchLater));
        }
        assertFalse(repository.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, muchLater));
    }

    @Test
    void tokenBucketStateIsIsolatedPerKey() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();
        Instant now = Instant.ofEpochMilli(0);

        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowTokenBucket("user-a:endpoint:rule", TOKEN_RULE, now));
        }
        assertFalse(repository.allowTokenBucket("user-a:endpoint:rule", TOKEN_RULE, now));
        assertTrue(repository.allowTokenBucket("user-b:endpoint:rule", TOKEN_RULE, now));
    }

    @Test
    void slidingWindowRejectsWhenEffectiveCountReachesLimit() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();
        Instant now = Instant.ofEpochMilli(1_000);

        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, now));
        }
        assertFalse(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, now));
    }

    @Test
    void slidingWindowCarriesPreviousWindowWeight() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();

        Instant previousWindow = Instant.ofEpochMilli(0);
        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, previousWindow));
        }

        Instant middleOfNextWindow = Instant.ofEpochMilli(15_000);
        for (int i = 0; i < 5; i++) {
            assertTrue(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, middleOfNextWindow));
        }
        assertFalse(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, middleOfNextWindow));
    }

    @Test
    void slidingWindowDropsPreviousCountAfterSkippedWindow() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();

        Instant firstWindow = Instant.ofEpochMilli(0);
        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, firstWindow));
        }

        Instant afterSkippingOneWindow = Instant.ofEpochMilli(25_000);
        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, afterSkippingOneWindow));
        }
        assertFalse(repository.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, afterSkippingOneWindow));
    }

    @Test
    void slidingWindowStateIsIsolatedPerKey() {
        InMemoryRateLimiterRepository repository = new InMemoryRateLimiterRepository();
        Instant now = Instant.ofEpochMilli(1_000);

        for (int i = 0; i < 10; i++) {
            assertTrue(repository.allowSlidingWindowCounter("user-a:endpoint:rule", WINDOW_RULE, now));
        }
        assertFalse(repository.allowSlidingWindowCounter("user-a:endpoint:rule", WINDOW_RULE, now));
        assertTrue(repository.allowSlidingWindowCounter("user-b:endpoint:rule", WINDOW_RULE, now));
    }
}
