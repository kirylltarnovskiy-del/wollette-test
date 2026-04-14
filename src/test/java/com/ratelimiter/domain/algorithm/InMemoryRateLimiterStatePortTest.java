package com.ratelimiter.domain.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterStatePortTest {

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
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();
        Instant now = Instant.ofEpochMilli(0);

        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, now));
        }
        assertFalse(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, now));
    }

    @Test
    void tokenBucketRefillsBasedOnElapsedTime() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();
        Instant t0 = Instant.ofEpochMilli(0);

        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, t0));
        }
        assertFalse(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, t0));

        Instant tPlus3Sec = Instant.ofEpochMilli(3_000);
        assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
        assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
        assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
        assertFalse(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, tPlus3Sec));
    }

    @Test
    void tokenBucketRefillNeverExceedsBurstCapacity() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();
        Instant t0 = Instant.ofEpochMilli(0);

        assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, t0));

        Instant muchLater = Instant.ofEpochMilli(1_000_000);
        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, muchLater));
        }
        assertFalse(statePort.allowTokenBucket("user:endpoint:rule", TOKEN_RULE, muchLater));
    }

    @Test
    void tokenBucketStateIsIsolatedPerKey() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();
        Instant now = Instant.ofEpochMilli(0);

        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowTokenBucket("user-a:endpoint:rule", TOKEN_RULE, now));
        }
        assertFalse(statePort.allowTokenBucket("user-a:endpoint:rule", TOKEN_RULE, now));
        assertTrue(statePort.allowTokenBucket("user-b:endpoint:rule", TOKEN_RULE, now));
    }

    @Test
    void slidingWindowRejectsWhenEffectiveCountReachesLimit() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();
        Instant now = Instant.ofEpochMilli(1_000);

        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, now));
        }
        assertFalse(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, now));
    }

    @Test
    void slidingWindowCarriesPreviousWindowWeight() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();

        Instant previousWindow = Instant.ofEpochMilli(0);
        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, previousWindow));
        }

        Instant middleOfNextWindow = Instant.ofEpochMilli(15_000);
        for (int i = 0; i < 5; i++) {
            assertTrue(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, middleOfNextWindow));
        }
        assertFalse(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, middleOfNextWindow));
    }

    @Test
    void slidingWindowDropsPreviousCountAfterSkippedWindow() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();

        Instant firstWindow = Instant.ofEpochMilli(0);
        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, firstWindow));
        }

        Instant afterSkippingOneWindow = Instant.ofEpochMilli(25_000);
        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, afterSkippingOneWindow));
        }
        assertFalse(statePort.allowSlidingWindowCounter("user:endpoint:rule", WINDOW_RULE, afterSkippingOneWindow));
    }

    @Test
    void slidingWindowStateIsIsolatedPerKey() {
        InMemoryRateLimiterStatePort statePort = new InMemoryRateLimiterStatePort();
        Instant now = Instant.ofEpochMilli(1_000);

        for (int i = 0; i < 10; i++) {
            assertTrue(statePort.allowSlidingWindowCounter("user-a:endpoint:rule", WINDOW_RULE, now));
        }
        assertFalse(statePort.allowSlidingWindowCounter("user-a:endpoint:rule", WINDOW_RULE, now));
        assertTrue(statePort.allowSlidingWindowCounter("user-b:endpoint:rule", WINDOW_RULE, now));
    }
}
