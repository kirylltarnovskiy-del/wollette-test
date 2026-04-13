package com.ratelimiter.infrastructure.stream;

import com.ratelimiter.analytics.AnalyticsAggregator;
import com.ratelimiter.domain.model.RateLimitEvent;
import com.ratelimiter.domain.model.UserTier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);

    private static final String STREAM_KEY = RedisStreamEventPublisher.STREAM_KEY;
    private final StringRedisTemplate redisTemplate;
    private final AnalyticsAggregator analyticsAggregator;
    private final String group;
    private final String consumer;
    private final long batchSize;

    public RedisStreamConsumer(
            StringRedisTemplate redisTemplate,
            AnalyticsAggregator analyticsAggregator,
            @Value("${rate-limiter.stream.group:analytics}") String group,
            @Value("${rate-limiter.stream.consumer:${HOSTNAME:${spring.application.name:rate-limiter-service}}}") String consumer,
            @Value("${rate-limiter.stream.batch-size:100}") long batchSize
    ) {
        this.redisTemplate = redisTemplate;
        this.analyticsAggregator = analyticsAggregator;
        this.group = group;
        this.consumer = consumer;
        this.batchSize = batchSize;
        createGroupIfMissing();
    }

    @Scheduled(fixedDelay = 100)
    public void consume() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(100)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    analyticsAggregator.record(toEvent(record));
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, group, record.getId());
                } catch (Exception ex) {
                    log.warn("Unable to process stream event {}", record.getId(), ex);
                }
            }
        }
        reclaimPending();
    }

    private void reclaimPending() {
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(STREAM_KEY, group);
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return;
        }
        PendingMessages pending = redisTemplate.opsForStream().pending(STREAM_KEY, Consumer.from(group, consumer), org.springframework.data.domain.Range.unbounded(), 20L);
        pending.forEach(message -> {
            if (message.getElapsedTimeSinceLastDelivery().toMillis() < 30_000) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                    STREAM_KEY,
                    group,
                    consumer,
                    Duration.ofSeconds(30),
                    message.getId()
            );
            if (claimed == null) {
                return;
            }
            for (MapRecord<String, Object, Object> record : claimed) {
                try {
                    analyticsAggregator.record(toEvent(record));
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, group, record.getId());
                } catch (Exception ex) {
                    log.warn("Failed to process reclaimed message {}", record.getId(), ex);
                }
            }
        });
    }

    private RateLimitEvent toEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> map = record.getValue();
        String userId = Objects.toString(map.get("userId"), "unknown");
        String endpoint = Objects.toString(map.get("endpoint"), "/unknown");
        boolean allowed = Boolean.parseBoolean(Objects.toString(map.get("allowed"), "false"));
        UserTier tier = UserTier.valueOf(Objects.toString(map.get("tier"), UserTier.FREE.name()));
        Instant timestamp = Instant.parse(Objects.toString(map.get("timestamp"), Instant.now().toString()));
        return new RateLimitEvent(userId, endpoint, allowed, tier, timestamp);
    }

    private void createGroupIfMissing() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), group);
        } catch (Exception ignored) {
            try {
                redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("init", "true")));
                redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), group);
            } catch (Exception alreadyExists) {
                // Group may already exist on another instance.
            }
        }
    }
}
