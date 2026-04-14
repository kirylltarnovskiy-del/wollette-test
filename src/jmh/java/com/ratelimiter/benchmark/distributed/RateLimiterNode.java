package com.ratelimiter.benchmark.distributed;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public class RateLimiterNode {

    private final String id;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private volatile long clockOffsetMs;

    public RateLimiterNode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public void simulateCrash() {
        healthy.set(false);
    }

    public void recover() {
        healthy.set(true);
    }

    public void setClockOffsetMs(long clockOffsetMs) {
        this.clockOffsetMs = clockOffsetMs;
    }

    public Instant now() {
        return Instant.now().plusMillis(clockOffsetMs);
    }
}
