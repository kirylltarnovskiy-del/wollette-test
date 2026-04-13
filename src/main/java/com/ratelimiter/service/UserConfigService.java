package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.model.UserTier;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserConfigService {

    private final RateLimiterProperties properties;

    public UserConfigService(RateLimiterProperties properties) {
        this.properties = properties;
    }

    public UserTier getTier(String userId) {
        if (userId == null || userId.isBlank()) {
            return UserTier.FREE;
        }
        String lower = userId.toLowerCase();
        if (lower.startsWith("ent-")) {
            return UserTier.ENTERPRISE;
        }
        if (lower.startsWith("pro-") || lower.startsWith("premium-")) {
            return UserTier.PREMIUM;
        }
        return UserTier.FREE;
    }

    public List<RateLimitRule> getRulesForTier(UserTier tier) {
        return properties.rulesFor(tier);
    }
}
