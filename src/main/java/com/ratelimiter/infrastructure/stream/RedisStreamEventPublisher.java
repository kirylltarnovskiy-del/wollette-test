package com.ratelimiter.infrastructure.stream;

import com.ratelimiter.domain.model.RateLimitEvent;
import com.ratelimiter.domain.port.AnalyticsEventPort;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamEventPublisher implements AnalyticsEventPort {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventPublisher.class);

    public static final String STREAM_KEY = "rate-limit-events";
    private final StringRedisTemplate redisTemplate;

    public RedisStreamEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(RateLimitEvent event) {
        try {
            redisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(Map.of(
                            "userId", event.userId(),
                            "endpoint", event.endpoint(),
                            "allowed", Boolean.toString(event.allowed()),
                            "tier", event.tier().name(),
                            "timestamp", event.timestamp().toString()
                    )).withStreamKey(STREAM_KEY)
            );
        } catch (Exception e) {
            // Analytics publishing is fire-and-forget. A transient Redis failure
            // (e.g. during a cluster master failover) must not surface as a 5xx
            // to the client. Accept the data loss and let the stream catch up
            // once the cluster recovers.
            log.warn("Failed to publish analytics event (stream unavailable): {}", e.getMessage());
        }
    }
}
