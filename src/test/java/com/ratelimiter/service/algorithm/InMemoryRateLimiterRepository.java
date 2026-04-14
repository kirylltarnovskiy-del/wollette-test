package com.ratelimiter.service.algorithm;

import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.repository.RedisRateLimiterRepository;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class InMemoryRateLimiterRepository extends RedisRateLimiterRepository {

    private final Map<String, BucketState> tokenBuckets = new ConcurrentHashMap<>();
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    InMemoryRateLimiterRepository() {
        super(null, null, null);
    }

    @Override
    public boolean allowTokenBucket(String key, RateLimitRule rule, Instant now) {
        BucketState state = tokenBuckets.computeIfAbsent(key, ignored -> new BucketState(rule.burstCapacity(), now.toEpochMilli()));
        synchronized (state) {
            long refillInterval = Math.max(1, rule.window().toMillis() / Math.max(1, rule.maxRequests()));
            long elapsed = Math.max(0, now.toEpochMilli() - state.lastRefillMs.get());
            long refillTokens = elapsed / refillInterval;
            if (refillTokens > 0) {
                state.tokens.set(Math.min(rule.burstCapacity(), state.tokens.get() + refillTokens));
                state.lastRefillMs.set(state.lastRefillMs.get() + refillTokens * refillInterval);
            }
            if (state.tokens.get() <= 0) {
                return false;
            }
            state.tokens.decrementAndGet();
            return true;
        }
    }

    @Override
    public boolean allowSlidingWindowCounter(String key, RateLimitRule rule, Instant now) {
        WindowState state = windows.computeIfAbsent(key, ignored -> new WindowState(rule.window().toMillis(), now.toEpochMilli()));
        synchronized (state) {
            long nowMs = now.toEpochMilli();
            long currentWindowStart = (nowMs / state.windowMs) * state.windowMs;
            if (state.currentWindowStart != currentWindowStart) {
                if (state.currentWindowStart == currentWindowStart - state.windowMs) {
                    state.previous = state.current;
                } else {
                    state.previous = 0;
                }
                state.current = 0;
                state.currentWindowStart = currentWindowStart;
            }
            double elapsed = nowMs - currentWindowStart;
            double prevWeight = 1.0 - (elapsed / state.windowMs);
            double effective = state.previous * prevWeight + state.current;
            if (effective >= rule.maxRequests()) {
                return false;
            }
            state.current++;
            return true;
        }
    }

    private static class BucketState {
        final AtomicLong tokens;
        final AtomicLong lastRefillMs;

        BucketState(long tokens, long lastRefillMs) {
            this.tokens = new AtomicLong(tokens);
            this.lastRefillMs = new AtomicLong(lastRefillMs);
        }
    }

    private static class WindowState {
        final long windowMs;
        long currentWindowStart;
        long current;
        long previous;

        WindowState(long windowMs, long nowMs) {
            this.windowMs = windowMs;
            this.currentWindowStart = (nowMs / windowMs) * windowMs;
        }
    }
}
