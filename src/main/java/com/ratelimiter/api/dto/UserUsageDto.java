package com.ratelimiter.api.dto;

public record UserUsageDto(String userId, long total, long accepted, long rejected) {
}
