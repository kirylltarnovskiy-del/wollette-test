package com.ratelimiter.controller;

import com.ratelimiter.dto.RequestDecisionDto;
import com.ratelimiter.dto.RequestDto;
import com.ratelimiter.dto.StatsDto;
import com.ratelimiter.dto.UserUsageDto;
import com.ratelimiter.model.StatsSnapshot;
import com.ratelimiter.model.UserUsage;
import com.ratelimiter.service.AnalyticsService;
import com.ratelimiter.service.RateLimiterService;
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
    private final AnalyticsService analyticsService;

    public RateLimiterController(
            RateLimiterService rateLimiterService,
            AnalyticsService analyticsService
    ) {
        this.rateLimiterService = rateLimiterService;
        this.analyticsService = analyticsService;
    }

    @PostMapping("/request")
    public ResponseEntity<RequestDecisionDto> request(@Valid @RequestBody RequestDto request) {
        boolean allowed = rateLimiterService.allowRequest(request.userId(), request.endpoint());
        RequestDecisionDto body = new RequestDecisionDto(allowed, request.userId(), request.endpoint());
        return ResponseEntity.status(allowed ? 200 : 429).body(body);
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsDto> stats(@RequestParam(defaultValue = "60") int windowSeconds) {
        StatsSnapshot snapshot = analyticsService.getStats(windowSeconds);
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
        UserUsage usage = analyticsService.getUserUsage(userId);
        return ResponseEntity.ok(new UserUsageDto(
                usage.userId(),
                usage.total(),
                usage.accepted(),
                usage.rejected()
        ));
    }
}
