package com.ratelimiter.service;

import com.ratelimiter.event.RedisStreamPublisher;
import com.ratelimiter.model.AlgorithmType;
import com.ratelimiter.model.RateLimitEvent;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.model.UserTier;
import com.ratelimiter.service.algorithm.RateLimitAlgorithm;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {

    private final UserConfigService userConfigService;
    private final Map<AlgorithmType, RateLimitAlgorithm> algorithms;
    private final RedisStreamPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public RateLimiterService(
            UserConfigService userConfigService,
            List<RateLimitAlgorithm> algorithmList,
            RedisStreamPublisher eventPublisher,
            MeterRegistry meterRegistry
    ) {
        this.userConfigService = userConfigService;
        this.algorithms = algorithmList.stream().collect(Collectors.toMap(RateLimitAlgorithm::type, Function.identity()));
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    public boolean allowRequest(String userId, String endpoint) {
        Timer.Sample sample = Timer.start(meterRegistry);
        MDC.put("userId", userId);
        MDC.put("endpoint", endpoint);
        UserTier tier = userConfigService.getTier(userId);
        boolean allowed = evaluateRules(userId, endpoint, userConfigService.getRulesForTier(tier));

        eventPublisher.publish(new RateLimitEvent(userId, endpoint, allowed, tier, Instant.now()));
        meterRegistry.counter("rate_limiter.requests.total",
                "result", allowed ? "allowed" : "rejected",
                "tier", tier.name().toLowerCase()).increment();
        sample.stop(Timer.builder("rate_limiter.decision.latency").register(meterRegistry));
        MDC.clear();
        return allowed;
    }

    private boolean evaluateRules(String userId, String endpoint, List<RateLimitRule> rules) {
        for (RateLimitRule rule : rules) {
            RateLimitAlgorithm algorithm = algorithms.get(rule.algorithm());
            if (algorithm == null) {
                continue;
            }
            String key = userId + ":" + endpoint + ":" + rule.name();
            if (!algorithm.allowRequest(key, rule)) {
                return false;
            }
        }
        return true;
    }
}
