package com.ratelimiter.application.config;

import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.model.UserTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    @Valid
    private Map<UserTier, List<RuleConfig>> tiers = new EnumMap<>(UserTier.class);

    public Map<UserTier, List<RuleConfig>> getTiers() {
        return tiers;
    }

    public void setTiers(Map<UserTier, List<RuleConfig>> tiers) {
        this.tiers = tiers;
    }

    public List<RateLimitRule> rulesFor(UserTier tier) {
        List<RuleConfig> configured = tiers.getOrDefault(tier, List.of());
        List<RateLimitRule> rules = new ArrayList<>(configured.size());
        for (RuleConfig rule : configured) {
            long burstCapacity = rule.getBurstCapacity() == null ? rule.getMaxRequests() : rule.getBurstCapacity();
            rules.add(new RateLimitRule(
                    rule.getName(),
                    rule.getWindow(),
                    rule.getMaxRequests(),
                    burstCapacity,
                    rule.getAlgorithm()
            ));
        }
        return rules;
    }

    public static class RuleConfig {
        @NotBlank
        private String name;
        @NotNull
        private Duration window;
        @Min(1)
        private long maxRequests;
        @Min(1)
        private Long burstCapacity;
        @NotNull
        private AlgorithmType algorithm;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public long getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(long maxRequests) {
            this.maxRequests = maxRequests;
        }

        public Long getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(Long burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public AlgorithmType getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(AlgorithmType algorithm) {
            this.algorithm = algorithm;
        }
    }
}
