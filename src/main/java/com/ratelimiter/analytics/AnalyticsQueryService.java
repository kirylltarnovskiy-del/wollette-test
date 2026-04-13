package com.ratelimiter.analytics;

import com.ratelimiter.analytics.model.StatsSnapshot;
import com.ratelimiter.analytics.model.UserUsage;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsQueryService {

    private final AnalyticsAggregator analyticsAggregator;

    public AnalyticsQueryService(AnalyticsAggregator analyticsAggregator) {
        this.analyticsAggregator = analyticsAggregator;
    }

    public StatsSnapshot getStats(int windowSeconds) {
        return analyticsAggregator.snapshot(windowSeconds);
    }

    public UserUsage getUserUsage(String userId) {
        return analyticsAggregator.userUsage(userId);
    }
}
