package com.ratelimiter.analytics;

import com.ratelimiter.analytics.model.StatsSnapshot;
import com.ratelimiter.analytics.model.TopUser;
import com.ratelimiter.analytics.model.UserUsage;
import com.ratelimiter.domain.model.RateLimitEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsAggregator {

    private static final int RING_SECONDS = 3600;
    private static final String TOP_USERS_KEY = "analytics:top-users";
    private static final String USER_USAGE_PREFIX = "analytics:user:";

    private final AtomicLong[] acceptedBySlot = initCounters();
    private final AtomicLong[] rejectedBySlot = initCounters();
    private final AtomicLong[] slotSecond = initCounters();
    private final StringRedisTemplate redisTemplate;

    public AnalyticsAggregator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void record(RateLimitEvent event) {
        long second = event.timestamp().getEpochSecond();
        int slot = (int) (second % RING_SECONDS);

        if (slotSecond[slot].get() != second) {
            acceptedBySlot[slot].set(0);
            rejectedBySlot[slot].set(0);
            slotSecond[slot].set(second);
        }

        if (event.allowed()) {
            acceptedBySlot[slot].incrementAndGet();
        } else {
            rejectedBySlot[slot].incrementAndGet();
        }

        redisTemplate.opsForZSet().incrementScore(TOP_USERS_KEY, event.userId(), 1.0D);
        String usageKey = USER_USAGE_PREFIX + event.userId();
        redisTemplate.opsForHash().increment(usageKey, "total", 1);
        redisTemplate.opsForHash().increment(usageKey, event.allowed() ? "accepted" : "rejected", 1);
        redisTemplate.expire(usageKey, java.time.Duration.ofDays(30));
    }

    public StatsSnapshot snapshot(int windowSeconds) {
        long now = Instant.now().getEpochSecond();
        long accepted = 0;
        long rejected = 0;
        Map<Long, Long> windowStats = new LinkedHashMap<>();
        int boundedWindow = Math.max(1, Math.min(windowSeconds, RING_SECONDS));

        for (int i = 0; i < boundedWindow; i++) {
            long second = now - i;
            int slot = (int) (second % RING_SECONDS);
            long secondCount = 0;
            if (slotSecond[slot].get() == second) {
                secondCount = acceptedBySlot[slot].get() + rejectedBySlot[slot].get();
                accepted += acceptedBySlot[slot].get();
                rejected += rejectedBySlot[slot].get();
            }
            windowStats.put(second, secondCount);
        }

        long total = accepted + rejected;
        long requestsPerSecond = total / boundedWindow;
        return new StatsSnapshot(
                requestsPerSecond,
                accepted,
                rejected,
                topUsers(10),
                windowStats
        );
    }

    public UserUsage userUsage(String userId) {
        String key = USER_USAGE_PREFIX + userId;
        long total = readHashCounter(key, "total");
        long accepted = readHashCounter(key, "accepted");
        long rejected = readHashCounter(key, "rejected");
        return new UserUsage(userId, total, accepted, rejected);
    }

    private long readHashCounter(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private List<TopUser> topUsers(int topK) {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(TOP_USERS_KEY, 0, topK - 1);
        List<TopUser> result = new ArrayList<>();
        if (tuples == null) {
            return result;
        }
        for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            result.add(new TopUser(tuple.getValue(), tuple.getScore().longValue()));
        }
        return result;
    }

    private static AtomicLong[] initCounters() {
        AtomicLong[] counters = new AtomicLong[RING_SECONDS];
        for (int i = 0; i < RING_SECONDS; i++) {
            counters[i] = new AtomicLong();
        }
        return counters;
    }
}
