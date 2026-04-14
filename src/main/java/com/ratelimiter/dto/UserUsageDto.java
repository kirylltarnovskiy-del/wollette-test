package com.ratelimiter.dto;

public record UserUsageDto(String userId, long total, long accepted, long rejected) {
}
