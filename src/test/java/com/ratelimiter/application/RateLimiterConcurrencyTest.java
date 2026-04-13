package com.ratelimiter.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.application.config.RateLimiterProperties;
import com.ratelimiter.domain.algorithm.RateLimitAlgorithm;
import com.ratelimiter.domain.algorithm.TokenBucketAlgorithm;
import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.model.UserTier;
import com.ratelimiter.domain.port.AnalyticsEventPort;
import com.ratelimiter.domain.port.RateLimiterStatePort;
import com.ratelimiter.domain.port.UserConfigPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterConcurrencyTest {

    @Test
    void acceptedRequestsNeverExceedConfiguredLimit() throws InterruptedException {
        int limit = 10;
        RateLimitRule rule = new RateLimitRule("per-second", Duration.ofSeconds(10), limit, limit, AlgorithmType.TOKEN_BUCKET);
        RateLimiterStatePort statePort = new LocalStatePort();
        RateLimitAlgorithm token = new TokenBucketAlgorithm(statePort);
        UserConfigPort userConfig = new UserConfigPort() {
            @Override
            public UserTier getTier(String userId) {
                return UserTier.FREE;
            }

            @Override
            public List<RateLimitRule> getRulesForTier(UserTier tier) {
                return List.of(rule);
            }
        };
        AnalyticsEventPort noOp = event -> { };
        RateLimiterService service = new RateLimiterService(
                userConfig,
                List.of(token),
                noOp,
                new SimpleMeterRegistry()
        );

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (service.allowRequest("free-user", "/request")) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdownNow();

        assertTrue(accepted.get() <= limit);
        assertEquals(threads, accepted.get() + rejected.get());
    }

    private static class LocalStatePort implements RateLimiterStatePort {
        private long tokens = 10;
        private long lastRefill = System.currentTimeMillis();

        @Override
        public synchronized boolean allowTokenBucket(String key, RateLimitRule rule, Instant now) {
            long interval = Math.max(1, rule.window().toMillis() / rule.maxRequests());
            long elapsed = Math.max(0, now.toEpochMilli() - lastRefill);
            long refill = elapsed / interval;
            if (refill > 0) {
                tokens = Math.min(rule.burstCapacity(), tokens + refill);
                lastRefill = lastRefill + (refill * interval);
            }
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }

        @Override
        public boolean allowSlidingWindowCounter(String key, RateLimitRule rule, Instant now) {
            return true;
        }
    }
}
