package com.ratelimiter.application;

import com.ratelimiter.domain.algorithm.RateLimitAlgorithm;
import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitEvent;
import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.model.UserTier;
import com.ratelimiter.domain.port.AnalyticsEventPort;
import com.ratelimiter.domain.port.UserConfigPort;
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

    private final UserConfigPort userConfigPort;
    private final Map<AlgorithmType, RateLimitAlgorithm> algorithms;
    private final AnalyticsEventPort eventPort;
    private final MeterRegistry meterRegistry;

    public RateLimiterService(
            UserConfigPort userConfigPort,
            List<RateLimitAlgorithm> algorithmList,
            AnalyticsEventPort eventPort,
            MeterRegistry meterRegistry
    ) {
        this.userConfigPort = userConfigPort;
        this.algorithms = algorithmList.stream().collect(Collectors.toMap(RateLimitAlgorithm::type, Function.identity()));
        this.eventPort = eventPort;
        this.meterRegistry = meterRegistry;
    }

    public boolean allowRequest(String userId, String endpoint) {
        Timer.Sample sample = Timer.start(meterRegistry);
        MDC.put("userId", userId);
        MDC.put("endpoint", endpoint);
        UserTier tier = userConfigPort.getTier(userId);
        boolean allowed = evaluateRules(userId, endpoint, userConfigPort.getRulesForTier(tier));

        eventPort.publish(new RateLimitEvent(userId, endpoint, allowed, tier, Instant.now()));
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
