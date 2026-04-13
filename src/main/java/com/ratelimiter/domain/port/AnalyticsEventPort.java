package com.ratelimiter.domain.port;

import com.ratelimiter.domain.model.RateLimitEvent;

public interface AnalyticsEventPort {
    void publish(RateLimitEvent event);
}
