package com.ratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.event.RedisStreamPublisher;
import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.model.UserTier;
import com.ratelimiter.service.algorithm.RateLimitAlgorithm;
import com.ratelimiter.service.algorithm.TokenBucketAlgorithm;
import com.ratelimiter.repository.RedisRateLimiterRepository;
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
        RedisRateLimiterRepository repository = new LocalRepository();
        RateLimitAlgorithm token = new TokenBucketAlgorithm(repository);
        UserConfigService userConfig = new UserConfigService(null) {
            @Override
            public UserTier getTier(String userId) {
                return UserTier.FREE;
            }

            @Override
            public List<RateLimitRule> getRulesForTier(UserTier tier) {
                return List.of(rule);
            }
        };
        RedisStreamPublisher noOpPublisher = new RedisStreamPublisher(null) {
            @Override
            public void publish(com.ratelimiter.model.RateLimitEvent event) { }
        };
        RateLimiterService service = new RateLimiterService(
                userConfig,
                List.of(token),
                noOpPublisher,
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

    private static class LocalRepository extends RedisRateLimiterRepository {
        private long tokens = 10;
        private long lastRefill = System.currentTimeMillis();

        LocalRepository() {
            super(null, null, null);
        }

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
