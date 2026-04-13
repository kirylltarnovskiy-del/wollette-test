package com.ratelimiter.analytics.model;

import java.util.List;
import java.util.Map;

public record StatsSnapshot(
        long requestsPerSecond,
        long accepted,
        long rejected,
        List<TopUser> topUsers,
        Map<Long, Long> windowStats
) {
}
