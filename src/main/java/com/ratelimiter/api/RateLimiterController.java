package com.ratelimiter.api;

import com.ratelimiter.analytics.AnalyticsQueryService;
import com.ratelimiter.analytics.model.StatsSnapshot;
import com.ratelimiter.analytics.model.UserUsage;
import com.ratelimiter.api.dto.RequestDecisionDto;
import com.ratelimiter.api.dto.RequestDto;
import com.ratelimiter.api.dto.StatsDto;
import com.ratelimiter.api.dto.UserUsageDto;
import com.ratelimiter.application.RateLimiterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateLimiterController {

    private final RateLimiterService rateLimiterService;
    private final AnalyticsQueryService analyticsQueryService;

    public RateLimiterController(
            RateLimiterService rateLimiterService,
            AnalyticsQueryService analyticsQueryService
    ) {
        this.rateLimiterService = rateLimiterService;
        this.analyticsQueryService = analyticsQueryService;
    }

    @PostMapping("/request")
    public ResponseEntity<RequestDecisionDto> request(@Valid @RequestBody RequestDto request) {
        boolean allowed = rateLimiterService.allowRequest(request.userId(), request.endpoint());
        RequestDecisionDto body = new RequestDecisionDto(allowed, request.userId(), request.endpoint());
        return ResponseEntity.status(allowed ? 200 : 429).body(body);
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsDto> stats(@RequestParam(defaultValue = "60") int windowSeconds) {
        StatsSnapshot snapshot = analyticsQueryService.getStats(windowSeconds);
        return ResponseEntity.ok(new StatsDto(
                snapshot.requestsPerSecond(),
                snapshot.accepted(),
                snapshot.rejected(),
                snapshot.topUsers(),
                snapshot.windowStats()
        ));
    }

    @GetMapping("/users/{id}/usage")
    public ResponseEntity<UserUsageDto> usage(@PathVariable("id") String userId) {
        UserUsage usage = analyticsQueryService.getUserUsage(userId);
        return ResponseEntity.ok(new UserUsageDto(
                usage.userId(),
                usage.total(),
                usage.accepted(),
                usage.rejected()
        ));
    }
}
