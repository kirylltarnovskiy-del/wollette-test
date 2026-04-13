package com.ratelimiter.api.dto;

import com.ratelimiter.analytics.model.TopUser;
import java.util.List;
import java.util.Map;

public record StatsDto(
        long requestsPerSecond,
        long accepted,
        long rejected,
        List<TopUser> topUsers,
        Map<Long, Long> windowStats
) {
}
