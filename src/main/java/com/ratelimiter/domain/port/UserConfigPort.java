package com.ratelimiter.domain.port;

import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.model.UserTier;
import java.util.List;

public interface UserConfigPort {
    UserTier getTier(String userId);
    List<RateLimitRule> getRulesForTier(UserTier tier);
}
