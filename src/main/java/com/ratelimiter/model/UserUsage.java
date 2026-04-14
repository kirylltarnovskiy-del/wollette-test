package com.ratelimiter.model;

public record UserUsage(String userId, long total, long accepted, long rejected) {
}
