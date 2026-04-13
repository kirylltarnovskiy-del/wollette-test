package com.ratelimiter.infrastructure.redis;

import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.port.RateLimiterStatePort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiterAdapter implements RateLimiterStatePort {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> tokenBucketScript;
    private final DefaultRedisScript<Long> slidingWindowScript;

    public RedisRateLimiterAdapter(
            RedisTemplate<String, String> redisTemplate,
            DefaultRedisScript<Long> tokenBucketScript,
            DefaultRedisScript<Long> slidingWindowScript
    ) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.slidingWindowScript = slidingWindowScript;
    }

    @Override
    @CircuitBreaker(name = "redisRateLimiter", fallbackMethod = "allowOnFailure")
    public boolean allowTokenBucket(String key, RateLimitRule rule, Instant now) {
        String bucketKey = "rl:tb:{" + key + "}";
        List<String> keys = List.of(bucketKey);
        String nowMs = String.valueOf(now.toEpochMilli());
        String refillMs = String.valueOf(Math.max(1, rule.window().toMillis() / Math.max(1, rule.maxRequests())));
        Long result = redisTemplate.execute(
                tokenBucketScript,
                keys,
                String.valueOf(rule.maxRequests()),
                String.valueOf(rule.burstCapacity()),
                nowMs,
                refillMs
        );
        return result != null && result == 1L;
    }

    @Override
    @CircuitBreaker(name = "redisRateLimiter", fallbackMethod = "allowOnFailure")
    public boolean allowSlidingWindowCounter(String key, RateLimitRule rule, Instant now) {
        long windowSec = Math.max(1, rule.window().toSeconds());
        long nowSec = now.getEpochSecond();
        long currentWindowStart = (nowSec / windowSec) * windowSec;
        long previousWindowStart = currentWindowStart - windowSec;
        long elapsedInWindow = nowSec - currentWindowStart;

        String currentKey = "rl:sw:{" + key + "}:" + currentWindowStart;
        String previousKey = "rl:sw:{" + key + "}:" + previousWindowStart;

        Long result = redisTemplate.execute(
                slidingWindowScript,
                List.of(currentKey, previousKey),
                String.valueOf(rule.maxRequests()),
                String.valueOf(windowSec),
                String.valueOf(elapsedInWindow)
        );
        return result != null && result == 1L;
    }

    @SuppressWarnings("unused")
    private boolean allowOnFailure(String key, RateLimitRule rule, Instant now, Throwable throwable) {
        return true;
    }
}
