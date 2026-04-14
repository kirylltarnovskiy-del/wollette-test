package com.ratelimiter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ratelimiter.event.RedisStreamPublisher;
import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitEvent;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.model.UserTier;
import com.ratelimiter.service.algorithm.RateLimitAlgorithm;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RateLimiterServiceTest {

    @Test
    void allowsWhenAllRulesPassAndPublishesAllowedEvent() {
        StubUserConfigService userConfig = new StubUserConfigService(
                UserTier.FREE,
                List.of(
                        new RateLimitRule("r1", Duration.ofSeconds(1), 10, 10, AlgorithmType.TOKEN_BUCKET),
                        new RateLimitRule("r2", Duration.ofSeconds(1), 10, 10, AlgorithmType.SLIDING_WINDOW_COUNTER)
                )
        );
        RecordingAlgorithm token = new RecordingAlgorithm(AlgorithmType.TOKEN_BUCKET, true);
        RecordingAlgorithm sliding = new RecordingAlgorithm(AlgorithmType.SLIDING_WINDOW_COUNTER, true);
        RecordingStreamPublisher publisher = new RecordingStreamPublisher();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimiterService service = new RateLimiterService(userConfig, List.of(token, sliding), publisher, meterRegistry);

        boolean allowed = service.allowRequest("user-1", "/payments");

        assertTrue(allowed);
        assertEquals(1, token.calls.get());
        assertEquals(1, sliding.calls.get());
        assertEquals("user-1:/payments:r1", token.lastKey.get());
        assertEquals("user-1:/payments:r2", sliding.lastKey.get());
        assertNotNull(publisher.lastEvent.get());
        assertTrue(publisher.lastEvent.get().allowed());
        assertEquals(UserTier.FREE, publisher.lastEvent.get().tier());
        assertEquals(1.0, meterRegistry.get("rate_limiter.requests.total")
                .tag("result", "allowed")
                .tag("tier", "free")
                .counter()
                .count());
    }

    @Test
    void rejectsWhenAnyRuleFailsAndShortCircuitsRemainingRules() {
        StubUserConfigService userConfig = new StubUserConfigService(
                UserTier.PREMIUM,
                List.of(
                        new RateLimitRule("first", Duration.ofSeconds(1), 10, 10, AlgorithmType.TOKEN_BUCKET),
                        new RateLimitRule("second", Duration.ofSeconds(1), 10, 10, AlgorithmType.SLIDING_WINDOW_COUNTER)
                )
        );
        RecordingAlgorithm token = new RecordingAlgorithm(AlgorithmType.TOKEN_BUCKET, false);
        RecordingAlgorithm sliding = new RecordingAlgorithm(AlgorithmType.SLIDING_WINDOW_COUNTER, true);
        RecordingStreamPublisher publisher = new RecordingStreamPublisher();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimiterService service = new RateLimiterService(userConfig, List.of(token, sliding), publisher, meterRegistry);

        boolean allowed = service.allowRequest("user-2", "/api");

        assertFalse(allowed);
        assertEquals(1, token.calls.get());
        assertEquals(0, sliding.calls.get());
        assertNotNull(publisher.lastEvent.get());
        assertFalse(publisher.lastEvent.get().allowed());
        assertEquals(1.0, meterRegistry.get("rate_limiter.requests.total")
                .tag("result", "rejected")
                .tag("tier", "premium")
                .counter()
                .count());
    }

    @Test
    void ignoresRuleWhenNoMatchingAlgorithmIsConfigured() {
        StubUserConfigService userConfig = new StubUserConfigService(
                UserTier.ENTERPRISE,
                List.of(new RateLimitRule("missing", Duration.ofSeconds(1), 10, 10, AlgorithmType.SLIDING_WINDOW_COUNTER))
        );
        RecordingStreamPublisher publisher = new RecordingStreamPublisher();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimiterService service = new RateLimiterService(
                userConfig,
                List.of(new RecordingAlgorithm(AlgorithmType.TOKEN_BUCKET, true)),
                publisher,
                meterRegistry
        );

        boolean allowed = service.allowRequest("user-3", "/health");

        assertTrue(allowed);
        assertNotNull(publisher.lastEvent.get());
        assertTrue(publisher.lastEvent.get().allowed());
        assertEquals(1.0, meterRegistry.get("rate_limiter.requests.total")
                .tag("result", "allowed")
                .tag("tier", "enterprise")
                .counter()
                .count());
    }

    private static final class StubUserConfigService extends UserConfigService {
        private final UserTier tier;
        private final List<RateLimitRule> rules;

        private StubUserConfigService(UserTier tier, List<RateLimitRule> rules) {
            super(null);
            this.tier = tier;
            this.rules = rules;
        }

        @Override
        public UserTier getTier(String userId) {
            return tier;
        }

        @Override
        public List<RateLimitRule> getRulesForTier(UserTier tier) {
            return rules;
        }
    }

    private static final class RecordingAlgorithm implements RateLimitAlgorithm {
        private final AlgorithmType type;
        private final boolean result;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastKey = new AtomicReference<>();

        private RecordingAlgorithm(AlgorithmType type, boolean result) {
            this.type = type;
            this.result = result;
        }

        @Override
        public boolean allowRequest(String key, RateLimitRule rule) {
            calls.incrementAndGet();
            lastKey.set(key);
            return result;
        }

        @Override
        public AlgorithmType type() {
            return type;
        }
    }

    private static final class RecordingStreamPublisher extends RedisStreamPublisher {
        private final AtomicReference<RateLimitEvent> lastEvent = new AtomicReference<>();

        private RecordingStreamPublisher() {
            super(null);
        }

        @Override
        public void publish(RateLimitEvent event) {
            lastEvent.set(event);
        }
    }
}
