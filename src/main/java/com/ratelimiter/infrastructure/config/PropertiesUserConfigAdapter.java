package com.ratelimiter.infrastructure.config;

import com.ratelimiter.application.config.RateLimiterProperties;
import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.model.UserTier;
import com.ratelimiter.domain.port.UserConfigPort;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PropertiesUserConfigAdapter implements UserConfigPort {

    private final RateLimiterProperties properties;

    public PropertiesUserConfigAdapter(RateLimiterProperties properties) {
        this.properties = properties;
    }

    @Override
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

    @Override
    public List<RateLimitRule> getRulesForTier(UserTier tier) {
        return properties.rulesFor(tier);
    }
}
