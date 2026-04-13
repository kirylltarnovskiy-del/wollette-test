package com.ratelimiter.analytics.model;

public record UserUsage(String userId, long total, long accepted, long rejected) {
}
